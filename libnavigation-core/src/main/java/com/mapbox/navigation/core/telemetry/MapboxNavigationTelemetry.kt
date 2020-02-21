package com.mapbox.navigation.core.telemetry

import android.content.Context
import android.location.Location
import android.os.Build
import com.google.gson.Gson
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.telemetry.AppUserTurnstile
import com.mapbox.android.telemetry.MapboxTelemetry
import com.mapbox.android.telemetry.TelemetryUtils
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.logger.model.Message
import com.mapbox.navigation.base.logger.model.Tag
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.BuildConfig
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.fasterroute.FasterRouteObserver
import com.mapbox.navigation.core.metrics.MetricEvent
import com.mapbox.navigation.core.metrics.MetricsReporter
import com.mapbox.navigation.core.telemetry.telemetryevents.MAPBOX_NAVIGATION_SDK_IDENTIFIER
import com.mapbox.navigation.core.telemetry.telemetryevents.MAPBOX_NAVIGATION_UI_SDK_IDENTIFIER
import com.mapbox.navigation.core.telemetry.telemetryevents.MOCK_PROVIDER
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryArrival
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryCancel
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryDepartureEvent
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryFasterRoute
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryMetadata
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryReroute
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryStep
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryUserFeedback
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.TripSessionStateObserver
import com.mapbox.navigation.logger.MapboxLogger
import com.mapbox.navigation.utils.exceptions.NavigationException
import com.mapbox.navigation.utils.thread.ThreadController
import com.mapbox.navigation.utils.thread.ifChannelException
import com.mapbox.navigation.utils.thread.monitorChannelWithException
import com.mapbox.navigation.utils.time.Time
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly

private data class DynamicalyUpdatedRouteValues(
    var distanceRemaining: AtomicLong,
    var timeRemaining: AtomicInteger,
    var rerouteCount: AtomicInteger,
    var routeCanceled: AtomicBoolean,
    var routeArrived: AtomicBoolean,
    var offRouteCount: AtomicInteger,
    val timeOfRerouteEvent: AtomicLong
)

/**
 * The one and only Telemetry class. This class handles all telemetry events.
 * Event List:
- appUserTurnstile
- navigation.depart
- navigation.feedback
- navigation.reroute
- navigation.fasterRoute
- navigation.arrive
 The class must be initialized before any telemetry events are reported. Attempting to use telemetry before initialization is called will throw an exception. Initialization maybe called multiple times, the call is idempotent.
 The class has two public methods, postUserFeedbackEvent() and initialize().
 */
internal object MapboxNavigationTelemetry : MapboxNavigationTelemetryInterface {
    // Public constants
    const val LOCATION_BUFFER_MAX_SIZE = 20
    const val MAX_TIME_LOCATION_COLLECTION = 20000L
    val TAG = Tag("MAPBOX_TELEMETRY")

    // Private variables
    private lateinit var context: Context
    private lateinit var mapboxToken: String

    private var metricsMetadata: TelemetryMetadata? = null // The metadata class required by every telemetry event
    private val jobControlIOScope = ThreadController.getIOScopeAndRootJob() // the job contoller used in this code. The code is single threaded all calls are performed on the Dispatchers.IO thread
    private val channelTelemetryEvent = Channel<MetricEvent>(Channel.CONFLATED) // used in testing to sample the events sent to the server
    private lateinit var metricsReporter: MetricsReporter // The legacy metrics reporter
    private var offRouteProcessing = AtomicBoolean(false) // A switch used to prevent multiple off-route events from generating events.

    /**
     * This class holds all mutable state of the Telemetry object
     */
    private val dynamicValues = DynamicalyUpdatedRouteValues(AtomicLong(0),
            AtomicInteger(0),
            AtomicInteger(0),
            AtomicBoolean(false),
            AtomicBoolean(false),
            AtomicInteger(0),
            AtomicLong(0))

    private lateinit var locationEngine: LocationEngine
    private lateinit var mapboxTelemetry: MapboxTelemetry

    private val CURRENT_SESSION_CONTROL: AtomicReference<CurrentSessionState> = AtomicReference(CurrentSessionState.SESSION_END) // A switch that maintains session state (start/end)

    private enum class CurrentSessionState {
        SESSION_START,
        SESSION_END
    }

    private val callbackDispatcher = TelemetryLocationAndProgressDispatcher() // The class responds to most notification events

    // **********  EVENT OBSERVERS ***************
    /**
     * Callback that monitors session start/stop. Session stop is interperted as both cancel and stop of the session
     */
    private val sessionStateObserver = object : TripSessionStateObserver {
        override fun onSessionStarted() {
            callbackDispatcher.markFirstLocation()
            handleSessionStart()
        }

        override fun onSessionStopped() {
            callbackDispatcher.unmarkFirstLocation()
            handleSessionCanceled()
            handleSessionStop()
        }
    }

    /**
     * Callback to observe faster route events
     */
    private val fasterRouteObserver = object : FasterRouteObserver {
        override fun onFasterRouteAvailable(fasterRoute: DirectionsRoute) {
            metricsMetadata?.let { metaData ->
                var telemetryStep: TelemetryStep? = null
                callbackDispatcher.getRouteProgress().routeProgress.currentLegProgress()?.let { routeProgress ->
                    telemetryStep = populateTelemetryStep(routeProgress)
                }
                metricsReporter.addEvent(TelemetryFasterRoute(
                        Metadata = metaData,
                        newDistanceRemaining = fasterRoute.distance()?.toInt() ?: -1,
                        newDurationRemaining = fasterRoute.duration()?.toInt() ?: -1,
                        newGeometry = fasterRoute.geometry(),
                        step = telemetryStep))
            }
        }
    }

    /**
     * Callback to observe off route events
     */
    private val rerouteObserver = object : OffRouteObserver {
        override fun onOffRouteStateChanged(offRoute: Boolean) {
            when (offRouteProcessing.compareAndSet(false, true)) {
                true -> {
                    MapboxLogger.i(TAG, Message("OffRoute message ignored. Previous message processing in progress"))
                    dynamicValues.timeOfRerouteEvent.set(Time.SystemImpl.millis())
                }
                false -> {
                    jobControlIOScope.scope.launch {
                        val offRouteBuffers = callbackDispatcher.getLocationBuffersAsync().await()
                        metricsMetadata?.let { telemetryMetadata ->
                            var timeSinceLastEvent = (Time.SystemImpl.millis() - dynamicValues.timeOfRerouteEvent.get()).toInt()
                            if (timeSinceLastEvent < 1000) {
                                timeSinceLastEvent = 0
                            }
                            metricsReporter.addEvent(TelemetryReroute(newDistanceRemaining = callbackDispatcher.getRouteProgress().routeProgress.durationRemaining().toInt(),
                                    locationsBefore = offRouteBuffers.first.toTypedArray(),
                                    locationsAfter = offRouteBuffers.second.toTypedArray(),
                                    Metadata = telemetryMetadata,
                                    feedbackId = TelemetryUtils.obtainUniversalUniqueIdentifier(),
                                    secondsSinceLastReroute = timeSinceLastEvent / 1000))
                        }
                        offRouteProcessing.set(false)
                    }
                }
            }
        }
    }
    /**
     * The lambda that is called if the SDK client did not initialize telemetry. If telemetry is not initialized
     * than calls to post a user feedback event will fail with this exception
     */
    // postUserFeedbackEvent
    private val postUserEventBeforeInit: (String, String, String, String?) -> Unit = { _, _, _, _ ->
        throw NavigationException("Telemetry must be initialized before calling this method. Call MapboxNavigationTelemetry.initialize()")
    }

    /**
     * The lambda that is called once telemetry is initialized.
     */
    private val postUserFeedbackEventAfterInit: (String, String, String, String?) -> Unit = { feedbackType, description, feedbackSource, screenShot -> postUserFeedbackHelper(feedbackType, description, feedbackSource, screenShot) }

    /**
     * The delegate lambda that dispatches either a pre or post initialization userFeedbackEvent
     */
    private var postUserEventDelegate = postUserEventBeforeInit

    /**
     * One-time initializer. Called in response to initialize() and then replaced with a no-op lambda to prevent multiple initialize() calls
     */
    private val primaryInitializer: (Context, String, MapboxNavigation, LocationEngine, MapboxTelemetry, LocationEngineRequest, MetricsReporter) -> Boolean = { context, token, mapboxNavigation, locationEngine, telemetry, locationEngineRequest, metricsReporter ->
        this.context = context
        mapboxToken = token
        this.locationEngine = locationEngine
        validateAccessToken(mapboxToken)
        this.metricsReporter = metricsReporter
        initializer = postInitialize // prevent primaryInitializer() from being called more than once.
        postUserEventDelegate = postUserFeedbackEventAfterInit // now that the object has been initialized we can post user feedback events
        registerForNotification(mapboxNavigation, locationEngineRequest)
        mapboxTelemetry = telemetry
        mapboxTelemetry.enable()
        postTurnstileEvent()
        true
    }
    private var initializer = primaryInitializer // The initialize dispatchers that points to either pre or post initialization lambda

    // Calling initialize multiple times does no harm. This call is a no-op.
    private var postInitialize: (Context, String, MapboxNavigation, LocationEngine, MapboxTelemetry, LocationEngineRequest, MetricsReporter) -> Boolean = { _, _, _, _, _, _, _ -> false }

    /**
     * This method must be called before using the Telemetry object
     */
    fun initialize(
        context: Context,
        mapboxToken: String,
        mapboxNavigation: MapboxNavigation,
        locationEngine: LocationEngine,
        telemetry: MapboxTelemetry,
        locationEngineRequest: LocationEngineRequest,
        metricsReporter: MetricsReporter
    ) = initializer(context, mapboxToken, mapboxNavigation, locationEngine, telemetry, locationEngineRequest, metricsReporter)

    /**
     * This method sends a user feedback event to the back-end servers. The method will suspend because the helper method [getLastNSecondsOfLocations] it calls is itself suspendable
     * The method may suspend for as long as 40 seconds.
     */
    override fun postUserFeedbackEvent(@FeedbackEvent.FeedbackType feedbackType: String, description: String, @FeedbackEvent.FeedbackSource feedbackSource: String, scrShot: String?) {
        postUserEventDelegate(feedbackType, description, feedbackSource, scrShot)
    }

    @TestOnly
    fun pauseTelemetry(flag: Boolean) {
        initializer = when (flag) {
            true -> {
                primaryInitializer
            }
            false -> {
                postInitialize
            }
        }
    }

    @TestOnly
    suspend fun dumpTelemetryJsonPayloadAsync(scope: CoroutineScope): Deferred<String> {
        val result = CompletableDeferred<String>()
        scope.monitorChannelWithException(channelTelemetryEvent, predicate = { event ->
            result.complete(Gson().toJson(event))
        })

        return result
    }

    /**
     * Helper class that posts user feedback. The call is available only after initialization
     */
    private fun postUserFeedbackHelper(@FeedbackEvent.FeedbackType feedbackType: String, description: String, @FeedbackEvent.FeedbackSource feedbackSource: String, scrShot: String?) {
        val lastProgress = callbackDispatcher.getRouteProgress()
        metricsMetadata?.let { metaData ->
            jobControlIOScope.scope.launch {
                val feedbackEvent = TelemetryUserFeedback(feedbackSource,
                        feedbackType,
                        description,
                        TelemetryUtils.retrieveVendorId(),
                        locationsBefore = callbackDispatcher.getLastNSecondsOfLocations().toTypedArray(),
                        locationsAfter = callbackDispatcher.getLastNSecondsOfLocations().toTypedArray(),
                        feedbackId = TelemetryUtils.obtainUniversalUniqueIdentifier(),
                        screenshot = scrShot,
                        step = lastProgress.routeProgress.currentLegProgress()?.let { populateTelemetryStep(it) },
                        Metadata = metaData
                )
                metricsReporter.addEvent(feedbackEvent)
            }
        }
    }
    /**
     * This method terminates the current session. The call is idempotent.
     */
    private fun endSession() {
        CURRENT_SESSION_CONTROL.compareAndSet(CurrentSessionState.SESSION_START, CurrentSessionState.SESSION_END).let { previousSessionState ->
            when (previousSessionState) {
                true -> {
                    sessionEndHelper()
                }
                false -> {
                    // Do nothing. A session cannot be ended twice and calling it multiple times has not detrimental effects
                }
            }
        }
    }

    /**
     * This method posts a cancel event in responce to onSessionEnd
     */
    private fun handleSessionCanceled() {
        dynamicValues.routeCanceled.set(true) // Set cancel state unconditionally
        when (dynamicValues.routeArrived.get()) {
            true -> {
                metricsMetadata?.let { telemetryData ->
                    val cancelEvent = TelemetryCancel(arrivalTimestamp = Date().toString(), Metadata = telemetryData)
                    metricsReporter.addEvent(cancelEvent)
                }
            }
            false -> {
                metricsMetadata?.let { telemetryData ->
                    val cancelEvent = TelemetryCancel(Metadata = telemetryData)
                    metricsReporter.addEvent(cancelEvent)
                }
            }
        }
    }

    /**
     * This method clears the state data for the Telemetry object in responce to session_end
     */
    private fun handleSessionStop() {
        callbackDispatcher.unmarkFirstLocation()
        dynamicValues.routeArrived.set(false)
        dynamicValues.routeCanceled.set(false)
        dynamicValues.distanceRemaining.set(0)
        dynamicValues.rerouteCount.set(0)
        dynamicValues.timeRemaining.set(0)
    }

    /**
     * This method starts a session. If a session is active it will terminate it, causing an stop/cancel event to be sent to the servers.
     * Every session start is guaranteed to have a sessoin end.
     */
    private fun handleSessionStart() {
        callbackDispatcher.getRouteProgress().routeProgress.route()?.let { directionsRoute ->
            // Expected session == SESSION_END
            CURRENT_SESSION_CONTROL.compareAndSet(CurrentSessionState.SESSION_END, CurrentSessionState.SESSION_START).let { previousSessionState ->
                when (previousSessionState) {
                    true -> {
                        sessionStartHelper(directionsRoute, callbackDispatcher.getLastLocation())
                    }
                    false -> {
                        endSession()
                        sessionStartHelper(directionsRoute, callbackDispatcher.getLastLocation())
                        MapboxLogger.e(TAG, Message("sessionEnd() not called. Calling it by default"))
                    }
                }
            }
        }
                ?: MapboxLogger.e(TAG, Message("Telemetry received a null DirectionsRoute. Session not started"))
    }

    /**
     * This method is used by a lambda. Since the Telemetry class is a singleton, U.I. elements may call postTurnstileEvent() before the singleton is initialized.
     * A labda guards against this possibility
     */
    private fun postTurnstileEvent() {
        // AppUserTurnstile is implemented in mapbox-telemetry-sdk
        val appUserTurnstileEvent = AppUserTurnstile(MAPBOX_NAVIGATION_SDK_IDENTIFIER, BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME) // TODO:OZ obtain the SDK identifier from MapboxNavigation
        val event = NavigationAppUserTurnstileEvent(appUserTurnstileEvent)
        metricsReporter.addEvent(event)
    }

    /**
     * This method starts a session. The start of a session does not result in a telemetry event being sent to the servers.
     * It is only the initialization of the [SessionState] object with two UUIDs
     */
    private fun sessionStartHelper(
        directionsRoute: DirectionsRoute,
        location: Location
    ) {
        jobControlIOScope.scope.launch {
            // Initialize identifiers unique to this session
            populateEventMetadata(directionsRoute, locationEngine, location).apply {
                sessionIdentifier = TelemetryUtils.obtainUniversalUniqueIdentifier()
                startTimestamp = Date().toString()
                metricsMetadata = this
            }
            telemetryDeparture(directionsRoute)?.let { metricEvent ->
                metricsReporter.addEvent(metricEvent)
            }
            monitorArrivalEvent()
        }
    }

    /**
     * This method waits for an [ROUTE_ARRIVED] event. Once received, it terminates the wait-loop and
     * sends the telemetry data to the servers.
     */
    private suspend fun monitorArrivalEvent() {
        var continueRunning = true
        while (coroutineContext.isActive && continueRunning) {
            try {
                val routeData = callbackDispatcher.getRouteProgressChannel().receive()
                when (routeData.routeProgress.currentState()) {
                    RouteProgressState.ROUTE_ARRIVED -> {
                        metricsMetadata?.apply {
                            lat = callbackDispatcher.getLastLocation().latitude.toFloat()
                            lng = callbackDispatcher.getLastLocation().longitude.toFloat()
                            distanceCompleted = routeData.routeProgress.distanceTraveled().toInt()
                        }
                        dynamicValues.routeCanceled.set(false)
                        metricsMetadata?.let { metaData ->
                            metricsReporter.addEvent(TelemetryArrival(arrivalTimestamp = Date().toString(), Metadata = metaData))
                        }
                        continueRunning = false
                    }
                    else -> {
                        // Do nothing
                    }
                }
            } catch (e: java.lang.Exception) {
                e.ifChannelException {
                    continueRunning = false
                }
            }
        }
    }

    private fun telemetryDeparture(directionsRoute: DirectionsRoute): MetricEvent? {
        metricsMetadata?.apply {
            lat = callbackDispatcher.getLastLocation().latitude.toFloat()
            lng = callbackDispatcher.getLastLocation().longitude.toFloat()
            originalRequestIdentifier = directionsRoute.routeOptions()?.requestUuid()
            requestIdentifier = directionsRoute.routeOptions()?.requestUuid()
            originalGeometry = directionsRoute.geometry()
        }
        metricsMetadata ?.let {
            return TelemetryDepartureEvent(it)
        }
        return null
    }

    /**
     * This method ends the session by setting CURRENT_SESSION_CONTROL to SESSION_END
     */
    private fun sessionEndHelper() {
        CURRENT_SESSION_CONTROL.set(CurrentSessionState.SESSION_END)
    }

    private fun registerForNotification(mapboxNavigation: MapboxNavigation, locationEngineRequest: LocationEngineRequest) {
        mapboxNavigation.registerOffRouteObserver(rerouteObserver)
        mapboxNavigation.registerRouteProgressObserver(callbackDispatcher)
        mapboxNavigation.registerTripSessionStateObserver(sessionStateObserver)
        mapboxNavigation.registerFasterRouteObserver(fasterRouteObserver)
        locationEngine.requestLocationUpdates(locationEngineRequest, callbackDispatcher, null)
    }

    private fun validateAccessToken(accessToken: String?) {
        if (accessToken.isNullOrEmpty() ||
                (!accessToken.toLowerCase(Locale.US).startsWith("pk.") &&
                        !accessToken.toLowerCase(Locale.US).startsWith("sk."))
        ) {
            throw NavigationException("A valid access token must be passed in when first initializing MapboxNavigation")
        }
    }

    private fun populateEventMetadata(
        directionsRoute: DirectionsRoute,
        locationEngine: LocationEngine,
        currentLocation: Location
    ): TelemetryMetadata {
        val isFromNavigationUi = false // TODO:OZ this must be set from MapboxNavigation when the UI SDK is ported and becomes available
        return TelemetryMetadata(
                created = Date().toString(),
                startTimestamp = Date().toString(),
                device = Build.DEVICE,
                sdkIdentifier = if (isFromNavigationUi) MAPBOX_NAVIGATION_UI_SDK_IDENTIFIER else MAPBOX_NAVIGATION_SDK_IDENTIFIER,
                sdkVersion = BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME,
                simulation = MOCK_PROVIDER == locationEngine.javaClass.name,
                locationEngine = locationEngine.javaClass.name,
                sessionIdentifier = TelemetryUtils.obtainUniversalUniqueIdentifier(),
                originalRequestIdentifier = directionsRoute.routeOptions()?.requestUuid(),
                requestIdentifier = directionsRoute.routeOptions()?.requestUuid(),
                lat = currentLocation.latitude.toFloat(),
                lng = currentLocation.longitude.toFloat(),
                originalGeometry = obtainGeometry(directionsRoute),
                originalEstimatedDistance = directionsRoute.distance()?.toInt() ?: 0,
                originalEstimatedDuration = directionsRoute.duration()?.toInt() ?: 0,
                originalStepCount = obtainStepCount(directionsRoute),
                geometry = obtainGeometry(directionsRoute),
                estimatedDistance = directionsRoute.distance()?.toInt() ?: 0,
                estimatedDuration = directionsRoute.duration()?.toInt() ?: 0,
                stepCount = obtainStepCount(directionsRoute),
                distanceCompleted = 0,
                distanceRemaining = dynamicValues.distanceRemaining.get().toInt(),
                absoluteDistanceToDestination = obtainAbsoluteDistance(callbackDispatcher.getLastLocation(), obtainRouteDestination(directionsRoute)),
                durationRemaining = callbackDispatcher.getRouteProgress().routeProgress.currentLegProgress()?.currentStepProgress()?.durationRemaining()?.toInt()
                        ?: 0,
                rerouteCount = dynamicValues.rerouteCount.get(),
                applicationState = TelemetryUtils.obtainApplicationState(context),
                batteryPluggedIn = TelemetryUtils.isPluggedIn(context),
                batteryLevel = TelemetryUtils.obtainBatteryLevel(context),
                connectivity = TelemetryUtils.obtainCellularNetworkType(context)
        )
    }
}
