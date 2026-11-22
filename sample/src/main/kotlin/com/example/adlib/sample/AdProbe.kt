package com.example.adlib.sample

import android.app.Activity
import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Calls the raw SDK (no gateway) and logs the thread of every callback via [ThreadProbe]. Google TEST ids only. */
class AdProbe(private val appContext: Context) {
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var appOpen: AppOpenAd? = null
    private var native: NativeAd? = null
    private var banner: BannerAd? = null

    /**
     * Probe only: initializes WITHOUT UMP consent. A real app must gather consent first
     * (see the Consent module). MobileAds.initialize is @WorkerThread, hence Dispatchers.IO.
     */
    fun initialize(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            ThreadProbe.record("init: caller before initialize")
            MobileAds.initialize(appContext, InitializationConfig.Builder(APP_ID).build()) {
                ThreadProbe.record("init: adapter listener")
            }
            ThreadProbe.record("init: initialize() returned")
        }
    }

    fun loadInterstitial() = InterstitialAd.load(
        AdRequest.Builder(INTERSTITIAL).build(),
        loadCallback("load interstitial") { interstitial = it },
    )

    fun loadRewarded() = RewardedAd.load(
        AdRequest.Builder(REWARDED).build(),
        loadCallback("load rewarded") { rewarded = it },
    )

    fun loadAppOpen() = AppOpenAd.load(
        AdRequest.Builder(APP_OPEN).build(),
        loadCallback("load appopen") { appOpen = it },
    )

    fun loadNative() = NativeAdLoader.load(
        NativeAdRequest.Builder(NATIVE, listOf(NativeAd.NativeAdType.NATIVE)).build(),
        object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) {
                ThreadProbe.record("load native: onNativeAdLoaded")
                nativeAd.adEventCallback = object : NativeAdEventCallback {
                    override fun onAdPaid(value: AdValue) = ThreadProbe.record("native: onAdPaid")

                    override fun onAdImpression() = ThreadProbe.record("native: onAdImpression")
                }
                native = nativeAd
            }

            override fun onAdFailedToLoad(adError: LoadAdError) =
                ThreadProbe.record("load native: failed ${adError.code}")
        },
    )

    fun loadBanner(activity: Activity, adView: AdView) {
        val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, BANNER_WIDTH_DP)
        adView.loadAd(
            BannerAdRequest.Builder(BANNER, size).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    ThreadProbe.record("load banner: onAdLoaded")
                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdPaid(value: AdValue) = ThreadProbe.record("banner: onAdPaid")

                        override fun onAdImpression() = ThreadProbe.record("banner: onAdImpression")
                    }
                    banner = ad
                }

                override fun onAdFailedToLoad(adError: LoadAdError) =
                    ThreadProbe.record("load banner: failed ${adError.code}")
            },
        )
    }

    fun showInterstitial(activity: Activity) {
        val ad = interstitial ?: return ThreadProbe.record("show interstitial: nothing loaded")
        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() = ThreadProbe.record("inter: onAdShowed")

            override fun onAdDismissedFullScreenContent() = ThreadProbe.record("inter: onAdDismissed")

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
                ThreadProbe.record("inter: onAdFailedToShow")

            override fun onAdImpression() = ThreadProbe.record("inter: onAdImpression")

            override fun onAdClicked() = ThreadProbe.record("inter: onAdClicked")

            override fun onAdPaid(value: AdValue) = ThreadProbe.record("inter: onAdPaid")
        }
        ad.show(activity)
        interstitial = null
    }

    fun showRewarded(activity: Activity) {
        val ad = rewarded ?: return ThreadProbe.record("show rewarded: nothing loaded")
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() = ThreadProbe.record("rewarded: onAdShowed")

            override fun onAdDismissedFullScreenContent() = ThreadProbe.record("rewarded: onAdDismissed")

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
                ThreadProbe.record("rewarded: onAdFailedToShow")

            override fun onAdImpression() = ThreadProbe.record("rewarded: onAdImpression")

            override fun onAdClicked() = ThreadProbe.record("rewarded: onAdClicked")

            override fun onAdPaid(value: AdValue) = ThreadProbe.record("rewarded: onAdPaid")
        }
        ad.show(
            activity,
            object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) =
                    ThreadProbe.record("rewarded: onUserEarnedReward")
            },
        )
        rewarded = null
    }

    fun showAppOpen(activity: Activity) {
        val ad = appOpen ?: return ThreadProbe.record("show appopen: nothing loaded")
        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() = ThreadProbe.record("appopen: onAdShowed")

            override fun onAdDismissedFullScreenContent() = ThreadProbe.record("appopen: onAdDismissed")

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
                ThreadProbe.record("appopen: onAdFailedToShow")

            override fun onAdImpression() = ThreadProbe.record("appopen: onAdImpression")

            override fun onAdClicked() = ThreadProbe.record("appopen: onAdClicked")

            override fun onAdPaid(value: AdValue) = ThreadProbe.record("appopen: onAdPaid")
        }
        ad.show(activity)
        appOpen = null
    }

    private fun <T> loadCallback(label: String, store: (T) -> Unit) = object : AdLoadCallback<T> {
        override fun onAdLoaded(ad: T) {
            ThreadProbe.record("$label: onAdLoaded")
            store(ad)
        }

        override fun onAdFailedToLoad(adError: LoadAdError) =
            ThreadProbe.record("$label: failed ${adError.code}")
    }

    private companion object {
        const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
        const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
        const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
        const val APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
        const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
        const val BANNER = "ca-app-pub-3940256099942544/9214589741"
        const val BANNER_WIDTH_DP = 360
    }
}
