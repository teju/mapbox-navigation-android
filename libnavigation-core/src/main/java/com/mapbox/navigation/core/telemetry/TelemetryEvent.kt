package com.mapbox.navigation.core.telemetry

interface TelemetryEvent {

    val eventId: String

    val sessionState: SessionState
}
