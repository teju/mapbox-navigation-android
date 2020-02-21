package com.mapbox.navigation.core.metrics

import android.annotation.SuppressLint
import com.mapbox.navigation.core.telemetry.NavigationEvent
import com.mapbox.navigation.core.telemetry.PhoneState

@SuppressLint("ParcelCreator")
internal class NavigationArriveEvent(
    phoneState: PhoneState
) : NavigationEvent(phoneState) {

    override fun getEventName(): String = NavigationMetrics.ARRIVE
}
