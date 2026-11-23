package com.example.adlib.core.events

import com.example.adlib.core.gateway.AdPaid

/** Why a loaded ad was thrown away instead of shown. */
enum class DropReason { LATE, EXPIRED, FLUSHED, CAPPED }

/** Everything the lib reports to the app (analytics, logging). [placement] is the placement name. */
sealed interface AdsEvent {
    val placement: String

    data class Loaded(override val placement: String, val adUnit: String) : AdsEvent
    data class LoadFailed(override val placement: String, val adUnit: String?, val code: String, val message: String) : AdsEvent
    data class Dropped(override val placement: String, val reason: DropReason) : AdsEvent
    data class Shown(override val placement: String) : AdsEvent
    data class Dismissed(override val placement: String) : AdsEvent
    data class Clicked(override val placement: String) : AdsEvent
    data class Paid(override val placement: String, val paid: AdPaid) : AdsEvent
    data class ConfigWarning(override val placement: String, val message: String) : AdsEvent
    /** A native was loaded but not shown on purpose (R27b). */
    data class HiddenOnPurpose(override val placement: String, val message: String) : AdsEvent
    data class WatchdogFired(override val placement: String, val what: String) : AdsEvent
}

/** Called on the lib's serial main dispatcher; keep it quick. Exceptions are caught and ignored. */
fun interface AdsEventListener {
    fun onEvent(event: AdsEvent)
}
