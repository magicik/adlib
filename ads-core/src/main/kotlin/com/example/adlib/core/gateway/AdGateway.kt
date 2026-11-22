package com.example.adlib.core.gateway

import android.app.Activity
import android.content.Context
import android.view.View

enum class AdFormat { INTERSTITIAL, REWARDED, APP_OPEN, NATIVE, BANNER }

/** Revenue event. [valueMicros] is in millionths of [currencyCode]; never sum across currencies. */
data class AdPaid(val valueMicros: Long, val currencyCode: String, val precisionType: String)

/** [code] is the SDK error code name (e.g. NO_FILL, TIMEOUT), passed through unchanged. */
sealed interface LoadResult<out T : AdHandle> {
    data class Loaded<T : AdHandle>(val handle: T) : LoadResult<T>

    data class Failed(val code: String, val message: String) : LoadResult<Nothing>
}

/** A loaded ad. [loadedAt] is epoch millis from the gateway's clock. */
interface AdHandle {
    val adUnitId: String
    val adFormat: AdFormat
    val loadedAt: Long

    fun destroy()
}

interface InterstitialHandle : AdHandle

interface RewardedHandle : AdHandle

interface AppOpenHandle : AdHandle

interface NativeHandle : AdHandle {
    val headline: String?
    val body: String?
    val callToAction: String?
    val advertiser: String?

    /** Escape hatch for ads-ui-compose / ads-ui-view to bind assets; the only place an SDK object can surface. */
    val sdkObject: Any
}

interface BannerHandle : AdHandle {
    /** The view hosting the banner; attach it to the hierarchy. Null in fakes. */
    val view: View?
}

data class AdLoadRequest(val adUnitId: String)

data class BannerLoadRequest(val adUnitId: String, val widthDp: Int)

/** Events common to every format. */
interface AdEventListener {
    fun onPaid(paid: AdPaid) {}

    fun onImpression() {}

    fun onClicked() {}
}

interface FullScreenAdListener : AdEventListener {
    fun onShowed() {}

    fun onDismissed() {}

    fun onFailedToShow(code: String, message: String) {}

    /** Rewarded only. */
    fun onUserEarnedReward(type: String, amount: Int) {}
}

/**
 * Isolates the Google SDK. No SDK type appears here. EVERY callback is delivered through one
 * [MainSerialDispatcher], in order, never inline.
 *
 * Load callbacks receive exactly one result per call, or none at all if the SDK never answers
 * (there is no load timeout and an in-flight request cannot be cancelled; callers must time out
 * themselves and ignore a late result - then destroy() it).
 */
interface AdGateway {
    fun loadInterstitial(request: AdLoadRequest, callback: (LoadResult<InterstitialHandle>) -> Unit)

    fun loadRewarded(request: AdLoadRequest, callback: (LoadResult<RewardedHandle>) -> Unit)

    fun loadAppOpen(request: AdLoadRequest, callback: (LoadResult<AppOpenHandle>) -> Unit)

    fun loadNative(request: AdLoadRequest, callback: (LoadResult<NativeHandle>) -> Unit)

    /** [context] must be an Activity context (adaptive banner size + AdView creation, main thread). */
    fun loadBanner(request: BannerLoadRequest, context: Context, callback: (LoadResult<BannerHandle>) -> Unit)

    fun showInterstitial(handle: InterstitialHandle, activity: Activity, listener: FullScreenAdListener)

    fun showRewarded(handle: RewardedHandle, activity: Activity, listener: FullScreenAdListener)

    fun showAppOpen(handle: AppOpenHandle, activity: Activity, listener: FullScreenAdListener)

    /** Paid/impression/click events for native and banner (full-screen formats use show). */
    fun setEventListener(handle: AdHandle, listener: AdEventListener)

    fun destroy(handle: AdHandle) = handle.destroy()
}
