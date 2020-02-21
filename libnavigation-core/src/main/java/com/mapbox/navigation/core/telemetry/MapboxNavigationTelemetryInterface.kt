package com.mapbox.navigation.core.telemetry

interface MapboxNavigationTelemetryInterface {
    fun postUserFeedbackEvent(
        @FeedbackEvent.FeedbackType feedbackType: String,
        description: String,
        @FeedbackEvent.FeedbackSource feedbackSource: String,
        scrShot: String?
    )
}
