package com.example.adlib.core

import android.app.Activity
import android.app.Application
import android.os.Build
import com.example.adlib.core.appopen.AppOpenResumeController
import com.example.adlib.core.appopen.AppOpenResumeOptions
import com.example.adlib.core.banner.BannerAdSource
import com.example.adlib.core.bootstrap.MobileAdsInitializer
import com.example.adlib.core.consent.ConsentGate
import com.example.adlib.core.consent.UmpConsentSource
import com.example.adlib.core.fullscreen.AppActivityTracker
import com.example.adlib.core.fullscreen.interstitial.InterstitialController
import com.example.adlib.core.fullscreen.rewarded.RewardedController
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.gateway.SdkAdGateway
import com.example.adlib.core.nativead.NativeAdSource
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Interstitial entry point (v3 4, 6.2). */
class InterstitialAds internal constructor(private val controller: InterstitialController) {
    /** Background fill; a failed load is retried by the lib, call again later to start over. */
    fun preload(placement: Placement) = controller.preload(placement)

    /** Shows an ad if one is allowed and ready, then runs [action] exactly once whatever happens. */
    fun showThen(activity: Activity, placement: Placement, action: () -> Unit): Job =
        controller.showThen(activity, placement, action)
}

/** Rewarded entry point (v3 4, 6.2). */
class RewardedAds internal constructor(private val controller: RewardedController) {
    fun preload(placement: Placement) = controller.preload(placement)

    fun isAvailable(placement: Placement): Flow<Boolean> = controller.isAvailable(placement)

    fun isAllowed(placement: Placement): Flow<Boolean> = controller.isAllowed(placement)

    /** Ends with exactly one of [onClosed] (an ad was shown) or [onUnavailable]; [onEarned] comes before [onClosed]. */
    fun request(
        activity: Activity,
        placement: Placement,
        onEarned: () -> Unit,
        onClosed: () -> Unit,
        onUnavailable: () -> Unit,
    ): Job = controller.request(activity, placement, onEarned, onClosed, onUnavailable)
}

/** App Open on return to the app (v3 7). Does nothing when the app declared no App Open placement. */
class AppOpenAds internal constructor(private val controller: AppOpenResumeController?) {
    /** Starts watching returns to the app; call once, e.g. in `Application.onCreate` after install. */
    fun attachResume() {
        controller?.attachResume()
    }

    /** Skips the next return if it comes within [maxMs] (share sheet, picker, billing); expires by itself. */
    fun pauseForExternalFlow(maxMs: Long = AppOpenResumeOptions.DEFAULT_PAUSE_MS) {
        controller?.pauseForExternalFlow(maxMs)
    }
}

/** The library's entry point (v3 section 4). Call [install] once in `Application.onCreate`. */
object Ads {
    private val lock = Any()

    @Volatile
    private var runtime: AdsRuntime? = null

    /**
     * Wires everything, synchronously and without network (T0). A second call is ignored, and so is a call
     * in a process other than the app's main one (L6). Throws [IllegalArgumentException] for a bad
     * placement list when [AdsOptions.debug].
     */
    fun install(application: Application, options: AdsOptions) {
        synchronized(lock) {
            if (runtime != null) return
            if (!isMainProcess(application)) return
            val validated = AdsRuntime.validate(options)
            val serial = MainSerialDispatcher()
            val tracker = AppActivityTracker.of(application)
            val scope = CoroutineScope(SupervisorJob() + serial)
            val created = AdsRuntime(
                options, validated, scope, serial,
                UmpConsentSource(application, options.consentDebug),
                MobileAdsInitializer(application, testDeviceIds = options.testDeviceIds),
                SdkAdGateway(serial, Clock.System),
                tracker, tracker, Clock.System,
            )
            tracker.addDestroyedListener { activity -> serial.post { created.consent.onActivityDestroyed(activity) } }
            runtime = created
        }
    }

    private fun rt(): AdsRuntime = checkNotNull(runtime) { "Ads.install(application, options) was not called in this process" }

    val consent: ConsentGate get() = rt().consent

    val isPremium: StateFlow<Boolean> get() = rt().state.isPremium

    fun setPremium(value: Boolean) = rt().state.setPremium(value)

    /** Turns every ad off (or back on) at once; read at load and at show. */
    fun setKillSwitch(value: Boolean) = rt().state.setKillSwitch(value)

    val interstitial: InterstitialAds get() = InterstitialAds(rt().interstitial)

    val rewarded: RewardedAds get() = RewardedAds(rt().rewarded)

    val appOpen: AppOpenAds get() = AppOpenAds(rt().appOpen)

    /** Source behind the Compose `NativeAd`; `LocalNativeAdSource` defaults to it. */
    val native: NativeAdSource get() = rt().native

    /** Source behind the Compose `BannerAd`; `LocalBannerAdSource` defaults to it. */
    val banner: BannerAdSource get() = rt().banner

    private fun isMainProcess(application: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim { it == '\u0000' } }.getOrNull()
        }
        return name == null || name == application.packageName
    }
}
