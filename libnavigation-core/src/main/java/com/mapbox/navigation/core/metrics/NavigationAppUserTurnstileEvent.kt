package com.mapbox.navigation.core.metrics

import com.google.gson.Gson
import com.mapbox.android.telemetry.AppUserTurnstile

internal class NavigationAppUserTurnstileEvent(
    val event: AppUserTurnstile
) : MetricEvent {

    override val metricName: String
        get() = NavigationMetrics.APP_USER_TURNSTILE

    override fun toJson(gson: Gson): String = gson.toJson(this)
}
