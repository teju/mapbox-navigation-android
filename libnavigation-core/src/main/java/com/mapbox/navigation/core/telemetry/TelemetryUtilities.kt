package com.mapbox.navigation.core.telemetry

import android.location.Location
import android.text.TextUtils
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.navigation.base.extensions.ifNonNull
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.core.telemetry.telemetryevents.TelemetryStep
import com.mapbox.navigation.utils.PRECISION_5
import com.mapbox.navigation.utils.PRECISION_6
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement

fun obtainGeometry(directionsRoute: DirectionsRoute?): String =
        ifNonNull(directionsRoute, directionsRoute?.geometry()) { _, geometry ->
            if (TextUtils.isEmpty(geometry)) {
                return@ifNonNull ""
            }
            val positions = PolylineUtils.decode(geometry, PRECISION_6)
            return@ifNonNull PolylineUtils.encode(positions, PRECISION_5)
        } ?: ""

fun obtainStepCount(directionsRoute: DirectionsRoute?): Int =
        ifNonNull(directionsRoute, directionsRoute?.legs()) { _, legs ->
            var stepCount = 0
            for (leg in legs) {
                stepCount += leg.steps()?.size ?: 0
            }
            return@ifNonNull stepCount
        } ?: 0

fun obtainAbsoluteDistance(
    currentLocation: Location,
    finalPoint: Point
): Int {
    val currentPoint = Point.fromLngLat(currentLocation.longitude, currentLocation.latitude)
    return TurfMeasurement.distance(currentPoint, finalPoint, TurfConstants.UNIT_METERS)
            .toInt()
}

fun obtainRouteDestination(route: DirectionsRoute): Point =
        route.legs()?.lastOrNull()?.steps()?.lastOrNull()?.maneuver()?.location()
                ?: Point.fromLngLat(0.0, 0.0)

fun populateTelemetryStep(legProgress: RouteLegProgress): TelemetryStep {
    return TelemetryStep(legProgress.upcomingStep()?.maneuver()?.instruction() ?: "",
            legProgress.upcomingStep()?.maneuver()?.type() ?: "",
            legProgress.upcomingStep()?.maneuver()?.type() ?: "",
            legProgress.upcomingStep()?.name() ?: "",

            legProgress.currentStepProgress()?.step()?.maneuver()?.instruction() ?: "",
            legProgress.currentStepProgress()?.step()?.maneuver()?.type() ?: "",
            legProgress.currentStepProgress()?.step()?.maneuver()?.type() ?: "",
            legProgress.currentStepProgress()?.step()?.name() ?: "",

            legProgress.currentStepProgress()?.distanceTraveled()?.toInt() ?: 0,
            legProgress.currentStepProgress()?.durationRemaining()?.toInt() ?: 0,
            legProgress.upcomingStep()?.distance()?.toInt() ?: 0,
            legProgress.upcomingStep()?.duration()?.toInt() ?: 0
            )
}
