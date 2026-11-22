package com.example.adlib.core.gateway

import android.app.Activity
import android.content.Context
import android.view.View
import com.example.adlib.core.time.Clock
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback

/**
 * [AdGateway] backed by ads-mobile-sdk 1.7.0. MobileAds.initialize must have been called
 * (on a worker thread) before the first load. Every SDK callback is re-posted through [dispatcher].
 *
 * Rewarded: the SDK 1.7.0 has ONE load signature, `RewardedAd.load(AdRequest, AdLoadCallback)`,
 * and ONE show signature, `show(Activity, OnUserEarnedRewardListener)`. The old
 * `load(Context, String, AdRequest, RewardedAdLoadCallback)` form does not exist in Next-Gen.
 */
class SdkAdGateway(
    private val dispatcher: MainSerialDispatcher = MainSerialDispatcher(),
    private val clock: Clock = Clock.System,
) : AdGateway {

    override fun loadInterstitial(request: AdLoadRequest, callback: (LoadResult<InterstitialHandle>) -> Unit) {
        InterstitialAd.load(
            AdRequest.Builder(request.adUnitId).build(),
            loadCallback(callback) { SdkInterstitial(it, request.adUnitId, clock.nowMs()) },
        )
    }

    override fun loadRewarded(request: AdLoadRequest, callback: (LoadResult<RewardedHandle>) -> Unit) {
        RewardedAd.load(
            AdRequest.Builder(request.adUnitId).build(),
            loadCallback(callback) { SdkRewarded(it, request.adUnitId, clock.nowMs()) },
        )
    }

    override fun loadAppOpen(request: AdLoadRequest, callback: (LoadResult<AppOpenHandle>) -> Unit) {
        AppOpenAd.load(
            AdRequest.Builder(request.adUnitId).build(),
            loadCallback(callback) { SdkAppOpen(it, request.adUnitId, clock.nowMs()) },
        )
    }

    override fun loadNative(request: AdLoadRequest, callback: (LoadResult<NativeHandle>) -> Unit) {
        val sdkRequest = NativeAdRequest.Builder(request.adUnitId, listOf(NativeAd.NativeAdType.NATIVE)).build()
        // Single-load only: multi-load is not supported with mediation (design v3 section 2).
        NativeAdLoader.load(
            sdkRequest,
            object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    val handle = SdkNative(nativeAd, request.adUnitId, clock.nowMs())
                    dispatcher.post { callback(LoadResult.Loaded(handle)) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val failed = adError.toFailed()
                    dispatcher.post { callback(failed) }
                }
            },
        )
    }

    override fun loadBanner(request: BannerLoadRequest, context: Context, callback: (LoadResult<BannerHandle>) -> Unit) {
        val adView = AdView(context)
        val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, request.widthDp)
        adView.loadAd(
            BannerAdRequest.Builder(request.adUnitId, size).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    val handle = SdkBanner(ad, adView, request.adUnitId, clock.nowMs())
                    dispatcher.post { callback(LoadResult.Loaded(handle)) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val failed = adError.toFailed()
                    dispatcher.post { callback(failed) }
                }
            },
        )
    }

    override fun showInterstitial(handle: InterstitialHandle, activity: Activity, listener: FullScreenAdListener) {
        val ad = (handle as SdkInterstitial).ad
        ad.adEventCallback = object : FullScreenBridge(listener), InterstitialAdEventCallback {}
        ad.show(activity)
    }

    override fun showRewarded(handle: RewardedHandle, activity: Activity, listener: FullScreenAdListener) {
        val ad = (handle as SdkRewarded).ad
        ad.adEventCallback = object : FullScreenBridge(listener), RewardedAdEventCallback {}
        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) {
                    val type = rewardItem.type
                    val amount = rewardItem.amount
                    dispatcher.post { listener.onUserEarnedReward(type, amount) }
                }
            },
        )
    }

    override fun showAppOpen(handle: AppOpenHandle, activity: Activity, listener: FullScreenAdListener) {
        val ad = (handle as SdkAppOpen).ad
        ad.adEventCallback = object : FullScreenBridge(listener), AppOpenAdEventCallback {}
        ad.show(activity)
    }

    override fun setEventListener(handle: AdHandle, listener: AdEventListener) {
        when (handle) {
            is SdkNative -> handle.ad.adEventCallback = object : NativeAdEventCallback {
                override fun onAdPaid(value: AdValue) = dispatcher.post { listener.onPaid(value.toPaid()) }

                override fun onAdImpression() = dispatcher.post { listener.onImpression() }

                override fun onAdClicked() = dispatcher.post { listener.onClicked() }
            }
            is SdkBanner -> handle.ad.adEventCallback = object : BannerAdEventCallback {
                override fun onAdPaid(value: AdValue) = dispatcher.post { listener.onPaid(value.toPaid()) }

                override fun onAdImpression() = dispatcher.post { listener.onImpression() }

                override fun onAdClicked() = dispatcher.post { listener.onClicked() }
            }
            else -> error("setEventListener is for native/banner; full-screen formats use show*")
        }
    }

    private inline fun <S : Ad, H : AdHandle> loadCallback(
        crossinline callback: (LoadResult<H>) -> Unit,
        crossinline wrap: (S) -> H,
    ) = object : AdLoadCallback<S> {
        override fun onAdLoaded(ad: S) {
            val handle = wrap(ad)
            dispatcher.post { callback(LoadResult.Loaded(handle)) }
        }

        override fun onAdFailedToLoad(adError: LoadAdError) {
            val failed = adError.toFailed()
            dispatcher.post { callback(failed) }
        }
    }

    /** Shared mapping of full-screen events; the SDK callback interfaces are all default-method. */
    private open inner class FullScreenBridge(private val listener: FullScreenAdListener) : AdEventCallback {
        override fun onAdShowedFullScreenContent() = dispatcher.post { listener.onShowed() }

        override fun onAdDismissedFullScreenContent() = dispatcher.post { listener.onDismissed() }

        override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
            val code = error.code.name
            val message = error.message
            dispatcher.post { listener.onFailedToShow(code, message) }
        }

        override fun onAdImpression() = dispatcher.post { listener.onImpression() }

        override fun onAdClicked() = dispatcher.post { listener.onClicked() }

        override fun onAdPaid(value: AdValue) = dispatcher.post { listener.onPaid(value.toPaid()) }
    }
}

private fun LoadAdError.toFailed() = LoadResult.Failed(code.name, message)

private fun AdValue.toPaid() = AdPaid(valueMicros, currencyCode, precisionType.name)

private abstract class SdkHandle(
    override val adUnitId: String,
    override val adFormat: AdFormat,
    override val loadedAt: Long,
    private val sdkAd: Ad,
) : AdHandle {
    override fun destroy() = sdkAd.destroy()
}

private class SdkInterstitial(val ad: InterstitialAd, unit: String, at: Long) :
    SdkHandle(unit, AdFormat.INTERSTITIAL, at, ad), InterstitialHandle

private class SdkRewarded(val ad: RewardedAd, unit: String, at: Long) :
    SdkHandle(unit, AdFormat.REWARDED, at, ad), RewardedHandle

private class SdkAppOpen(val ad: AppOpenAd, unit: String, at: Long) :
    SdkHandle(unit, AdFormat.APP_OPEN, at, ad), AppOpenHandle

private class SdkNative(val ad: NativeAd, unit: String, at: Long) :
    SdkHandle(unit, AdFormat.NATIVE, at, ad), NativeHandle {
    override val headline: String? get() = ad.headline
    override val body: String? get() = ad.body
    override val callToAction: String? get() = ad.callToAction
    override val advertiser: String? get() = ad.advertiser
    override val sdkObject: Any get() = ad
}

private class SdkBanner(val ad: BannerAd, private val adView: AdView, unit: String, at: Long) :
    SdkHandle(unit, AdFormat.BANNER, at, ad), BannerHandle {
    override val view: View get() = adView

    override fun destroy() = adView.destroy()
}
