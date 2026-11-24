package com.example.adlib.core

import com.example.adlib.core.appopen.AppOpenOverlay
import com.example.adlib.core.appopen.AppOpenResumeOptions
import com.example.adlib.core.bootstrap.AdsBootstrap
import com.example.adlib.core.consent.ConsentDebugConfig
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.fullscreen.interstitial.CapStore
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.PoolOptions
import com.example.adlib.core.state.ConfigProvider
import com.example.adlib.core.testmode.RevenueStore

/** Rules shared by the full-screen formats (v3 4, 6.4). */
data class FullScreenOptions(
    /** Quiet time after ANY full-screen ad closes. */
    val minGapAfterFullScreenMs: Long = 60_000L,
    /** Interstitial shows per day; 0 = unlimited. */
    val dailyCap: Int = 0,
    /** Interstitial: only every Nth attempt may show; 1 = every attempt. */
    val oneInN: Int = 1,
) {
    init {
        require(minGapAfterFullScreenMs >= 0) { "minGapAfterFullScreenMs must be >= 0" }
        require(dailyCap >= 0) { "dailyCap must be >= 0" }
        require(oneInN >= 1) { "oneInN must be >= 1" }
    }
}

/** Everything `Ads.install` needs (v3 section 4). */
class AdsOptions(
    /** Every placement of the app, declared up front. Duplicates throw when [debug], else are logged and skipped. */
    val placements: List<Placement>,
    /** Remote config, read on every load (R8). */
    val configProvider: ConfigProvider = ConfigProvider { null },
    val pool: PoolOptions = PoolOptions(),
    val fullScreen: FullScreenOptions = FullScreenOptions(),
    val appOpenResume: AppOpenResumeOptions = AppOpenResumeOptions(),
    /** Drawn while the first return waits for its ad; null = `DefaultAppOpenOverlay`. */
    val appOpenOverlay: AppOpenOverlay? = null,
    val testDeviceIds: List<String> = emptyList(),
    /** UMP debug geography / test devices; default none. */
    val consentDebug: ConsentDebugConfig = ConsentDebugConfig(),
    val debug: Boolean = false,
    /** The Premium flag the app stored; read at install so no ad is requested before it is known. */
    val initialPremium: Boolean = false,
    /** Persistence for test-mode detection; null = kept in memory only (resets with the process). */
    val revenueStore: RevenueStore? = null,
    /** Persistence for the interstitial caps; null = kept in memory only. */
    val capStore: CapStore? = null,
    /** Completes when the app's remote config is fetched; waited at most [AdsBootstrap.DEFAULT_CONFIG_WAIT_MS] from install (T3). */
    val awaitConfig: suspend () -> Unit = {},
    val listener: AdsEventListener? = null,
)
