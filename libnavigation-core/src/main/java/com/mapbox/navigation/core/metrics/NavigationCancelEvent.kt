package com.mapbox.navigation.core.metrics

import android.annotation.SuppressLint
import com.mapbox.navigation.core.telemetry.NavigationEvent
import com.mapbox.navigation.core.telemetry.PhoneState

@SuppressLint("ParcelCreator")
internal class NavigationCancelEvent(
    phoneState: PhoneState
) : NavigationEvent(phoneState) {
    /*
     * Don't remove any fields, cause they are should match with
     * the schema downloaded from S3. Look at {@link SchemaTest}
     */
    var arrivalTimestamp: String = ""
    var rating: Int = 0
    var comment: String = ""

    override fun getEventName(): String = NavigationMetrics.CANCEL_SESSION
}
