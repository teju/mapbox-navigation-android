package com.mapbox.navigation.core.metrics

import com.mapbox.android.telemetry.TelemetryUtils
import com.mapbox.navigation.core.telemetry.SessionState
import com.mapbox.navigation.core.telemetry.TelemetryEvent

class RerouteEvent(
    override var sessionState: SessionState
) : TelemetryEvent {
    override val eventId: String = TelemetryUtils.obtainUniversalUniqueIdentifier()
    var newRouteGeometry: String = ""
    var newDurationRemaining: Int = 0
    var newDistanceRemaining: Int = 0
}
