package com.mapbox.navigation.core.telemetry

import android.annotation.SuppressLint
import android.location.Location
import com.google.gson.Gson
import com.mapbox.navigation.core.metrics.NavigationStepData
import com.mapbox.navigation.core.metrics.RerouteEvent

@SuppressLint("ParcelCreator")
internal class NavigationRerouteEvent(
    phoneState: PhoneState,
    rerouteEvent: RerouteEvent,
    metricsRouteProgress: MetricsRouteProgress
) : NavigationEvent(phoneState) {

    companion object {
        private const val NAVIGATION_REROUTE = "navigation.reroute"
    }

    /*
     * Don't remove any fields, cause they are should match with
     * the schema downloaded from S3. Look at {@link SchemaTest}
     */
    val newDistanceRemaining: Int = rerouteEvent.newDistanceRemaining
    val newDurationRemaining: Int = rerouteEvent.newDurationRemaining
    val feedbackId: String = phoneState.feedbackId
    val newGeometry: String = rerouteEvent.newRouteGeometry
    val step: NavigationStepData = NavigationStepData(metricsRouteProgress)
    var secondsSinceLastReroute: Int = 0
    var locationsBefore: Array<Location>? = null
    var locationsAfter: Array<Location>? = null
    var screenshot: String? = null

    override fun getEventName(): String = NAVIGATION_REROUTE

    override fun toJson(gson: Gson): String = gson.toJson(this)
}
