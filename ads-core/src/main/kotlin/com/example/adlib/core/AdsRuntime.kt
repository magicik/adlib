package com.example.adlib.core

import com.example.adlib.core.appopen.AppOpenResumeController
import com.example.adlib.core.appopen.DefaultAppOpenOverlay
import com.example.adlib.core.banner.BannerController
import com.example.adlib.core.bootstrap.AdsBootstrap
import com.example.adlib.core.bootstrap.SdkInitializer
import com.example.adlib.core.consent.ConsentGate
import com.example.adlib.core.consent.ConsentSource
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.fullscreen.ActivityTracker
import com.example.adlib.core.fullscreen.ForegroundSource
import com.example.adlib.core.fullscreen.FullScreenGate
import com.example.adlib.core.fullscreen.FullScreenGateOptions
import com.example.adlib.core.fullscreen.interstitial.CapStore
import com.example.adlib.core.fullscreen.interstitial.InMemoryCapStore
import com.example.adlib.core.fullscreen.interstitial.InterstitialCaps
import com.example.adlib.core.fullscreen.interstitial.InterstitialController
import com.example.adlib.core.fullscreen.interstitial.InterstitialOptions
import com.example.adlib.core.fullscreen.rewarded.RewardedController
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.nativead.NativeController
import com.example.adlib.core.placement.PlacementRegistry
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.RevenueSnapshot
import com.example.adlib.core.testmode.RevenueStore
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CoroutineScope

/** Placements checked at install: [registry] plus the problems found when not strict (release). */
internal class ValidatedPlacements(val registry: PlacementRegistry, val warnings: List<String>)

/**
 * The wired object graph behind `Ads` (v3 section 4), built from injected parts so the whole flow runs
 * in a JVM test with fakes. [scope] must run on [serial], the one serial dispatcher (v3 6.7).
 */
internal class AdsRuntime(
    options: AdsOptions,
    validated: ValidatedPlacements,
    scope: CoroutineScope,
    serial: MainSerialDispatcher,
    consentSource: ConsentSource,
    initializer: SdkInitializer,
    gateway: AdGateway,
    tracker: ActivityTracker,
    foreground: ForegroundSource,
    clock: Clock,
    /** Used when [AdsOptions.capStore] is null; `Ads.install` passes the SharedPreferences store. */
    defaultCapStore: CapStore = InMemoryCapStore(),
) {
    val bus = AdsEventBus(serial, options.listener)
    val consent = ConsentGate(consentSource, scope)
    val state = AdsState(options.initialPremium, options.configProvider, bus) { consent.canRequestAds.value }
    val bootstrap = AdsBootstrap(consent.canRequestAds, initializer, scope, options.awaitConfig)
    private val testMode = TestMode(options.revenueStore ?: InMemoryRevenueStore(), bus)
    private val pool = AdPool(gateway, state, testMode, bootstrap::awaitReadyToLoad, bus, clock, scope, options.pool)
    private val gate = FullScreenGate(
        state.isPremium, tracker, bus, clock, scope,
        FullScreenGateOptions(minGapAfterFullScreenMs = options.fullScreen.minGapAfterFullScreenMs),
    )
    val interstitial: InterstitialController
    val rewarded = RewardedController(pool, gate, gateway, state, bus, testMode, clock, scope)

    /** Null when the app declared no App Open placement. */
    val appOpen: AppOpenResumeController?
    val native = NativeController(pool, gateway, state, testMode, bus, scope)
    val banner = BannerController(gateway, state, testMode, bus, bootstrap::awaitReadyToLoad, scope)

    init {
        val interOptions = InterstitialOptions(dailyCap = options.fullScreen.dailyCap, oneInN = options.fullScreen.oneInN)
        interstitial = InterstitialController(
            pool, gate, gateway, state, testMode,
            InterstitialCaps(interOptions, clock, testMode, options.capStore ?: defaultCapStore),
            tracker, bus, clock, scope, interOptions,
        )
        appOpen = validated.registry.all.firstOrNull { it.format == AdFormat.APP_OPEN }?.let {
            AppOpenResumeController(
                it, pool, gate, gateway, state, testMode, tracker, foreground, consent.formShowing,
                options.appOpenOverlay ?: DefaultAppOpenOverlay(), bus, clock, scope, options.appOpenResume,
            )
        }
        validated.warnings.forEach { bus.emit(AdsEvent.ConfigWarning("", it)) }
    }

    companion object {
        /** Throws [IllegalArgumentException] for a bad placement list when [AdsOptions.debug]; otherwise collects the problems. */
        fun validate(options: AdsOptions): ValidatedPlacements {
            val warnings = mutableListOf<String>()
            return ValidatedPlacements(PlacementRegistry(options.placements, options.debug, warnings::add), warnings)
        }
    }
}

private class InMemoryRevenueStore : RevenueStore {
    private var snapshot = RevenueSnapshot()

    override fun load() = snapshot

    override fun save(snapshot: RevenueSnapshot) {
        this.snapshot = snapshot
    }
}
