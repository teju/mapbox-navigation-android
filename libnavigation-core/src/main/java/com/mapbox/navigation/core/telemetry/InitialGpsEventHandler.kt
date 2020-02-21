package com.mapbox.navigation.core.telemetry

import com.mapbox.navigation.core.metrics.MetricsReporter

internal class InitialGpsEventHandler(
    private val metricsReporter: MetricsReporter
) {

    fun send(
        elapsedTime: Double,
        sessionId: String,
        metadata: NavigationPerformanceMetadata
    ) {
        val event = InitialGpsEvent(elapsedTime, sessionId, metadata)
        metricsReporter.addEvent(event)
    }
}
