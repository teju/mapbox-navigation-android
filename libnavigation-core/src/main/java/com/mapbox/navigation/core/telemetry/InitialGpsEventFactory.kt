package com.mapbox.navigation.core.telemetry

import com.mapbox.navigation.core.metrics.MetricsReporter
import com.mapbox.navigation.utils.time.ElapsedTime

internal class InitialGpsEventFactory(
    metricsReporter: MetricsReporter,
    private var time: ElapsedTime = ElapsedTime(),
    private val handler: InitialGpsEventHandler = InitialGpsEventHandler(metricsReporter)
) {

    private var sessionId = ""
    private var hasSent = false

    fun navigationStarted(sessionId: String) {
        this.sessionId = sessionId
        time.start()
    }

    fun gpsReceived(metadata: NavigationPerformanceMetadata) {
        if (time.start == null) {
            return
        }
        time.end()
        send(time, metadata)
    }

    fun reset() {
        time = ElapsedTime()
        hasSent = false
    }

    private fun send(time: ElapsedTime, metadata: NavigationPerformanceMetadata) {
        if (!hasSent && sessionId.isNotEmpty()) {
            val elapsedTime = time.elapsedTime
            handler.send(elapsedTime, sessionId, metadata)
            hasSent = true
        }
    }
}
