package com.example.adlib.core.testmode

import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.placement.Placement

/**
 * Lib-side test mode (v3 6.5); the SDK has no "this is a test device" API. Same rule as the old lib:
 * test mode = a native with a "Test Ad" headline was seen, OR paid events were seen but no currency
 * ever accumulated a positive total. A fresh install with no paid event is NOT test mode.
 * Confined to the serial dispatcher like the rest of the mutable state.
 */
internal class TestMode(
    private val store: RevenueStore,
    private val events: AdsEventBus,
) {
    private var snapshot: RevenueSnapshot = store.load()

    val isTestMode: Boolean
        get() = snapshot.nativeTestAdSeen ||
            (snapshot.hasPaidEvent && snapshot.totalMicrosByCurrency.values.none { it > 0L })

    /** R27a. */
    fun onNativeHeadline(headline: String?) {
        if (snapshot.nativeTestAdSeen || !isTestHeadline(headline)) return
        update(snapshot.copy(nativeTestAdSeen = true))
    }

    /** R27b accumulator: adds [paid] to its own currency; a zero value still counts as "a paid event happened". */
    fun onPaid(paid: AdPaid) {
        val totals = snapshot.totalMicrosByCurrency.toMutableMap()
        if (paid.valueMicros > 0L) {
            totals[paid.currencyCode] = (totals[paid.currencyCode] ?: 0L) + paid.valueMicros
        }
        update(snapshot.copy(totalMicrosByCurrency = totals, hasPaidEvent = true))
    }

    /**
     * R27b: while no revenue has ever been recorded (new device) a loaded native stays hidden until its
     * first paid impression. Emits an event each time it hides, so QA sees the spent request was intended.
     */
    fun shouldHideNative(placement: Placement): Boolean {
        val hide = !snapshot.hasPaidEvent && snapshot.totalMicrosByCurrency.values.none { it > 0L }
        if (hide) events.emit(AdsEvent.HiddenOnPurpose(placement.name, HIDDEN_R27B_MESSAGE))
        return hide
    }

    /** R26. */
    fun effectiveInterstitialIntervalMs(configMs: Long): Long =
        if (isTestMode) maxOf(configMs, TEST_MODE_MIN_INTERVAL_MS) else configMs

    /** R26: native refresh never runs against test inventory. */
    val nativeRefreshEnabled: Boolean
        get() = !isTestMode

    /** `gone_in_test_mode` applies to native / interstitial / app open, not rewarded (Q-C); banner is not covered. */
    fun isGone(format: AdFormat, config: PlacementConfig): Boolean =
        config.goneInTestMode && isTestMode && format in GONE_FORMATS

    private fun update(new: RevenueSnapshot) {
        snapshot = new
        store.save(new)
    }

    companion object {
        const val TEST_MODE_MIN_INTERVAL_MS = 60_000L
        const val HIDDEN_R27B_MESSAGE = "loaded but hidden on purpose (R27b)"
        private val GONE_FORMATS = setOf(AdFormat.NATIVE, AdFormat.INTERSTITIAL, AdFormat.APP_OPEN)

        fun isTestHeadline(headline: String?): Boolean =
            !headline.isNullOrBlank() && headline.contains("Test Ad", ignoreCase = true)
    }
}
