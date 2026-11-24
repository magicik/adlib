package com.example.adlib.sample

import android.app.Application
import com.example.adlib.core.Ads
import com.example.adlib.core.AdsOptions
import com.example.adlib.core.FullScreenOptions
import com.example.adlib.core.consent.ConsentDebugConfig
import com.example.adlib.core.consent.ConsentDebugGeography
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.placement.Placement

/** One placement per format, Google TEST ad unit ids only. */
object SamplePlacements {
    private fun placement(name: String, format: AdFormat, adUnit: String) = Placement(
        name,
        format,
        default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier(adUnit)))),
    )

    val interstitial = placement("inter_next", AdFormat.INTERSTITIAL, "ca-app-pub-3940256099942544/1033173712")
    val rewarded = placement("rewarded_unlock", AdFormat.REWARDED, "ca-app-pub-3940256099942544/5224354917")
    val appOpen = placement("app_open_resume", AdFormat.APP_OPEN, "ca-app-pub-3940256099942544/9257395921")
    val nativeLarge = placement("native_large", AdFormat.NATIVE, "ca-app-pub-3940256099942544/2247696110")
    val nativeSmall = placement("native_small", AdFormat.NATIVE, "ca-app-pub-3940256099942544/2247696110")
    val banner = placement("banner_bottom", AdFormat.BANNER, "ca-app-pub-3940256099942544/9214589741")

    val all = listOf(interstitial, rewarded, appOpen, nativeLarge, nativeSmall, banner)
}

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ads.install(
            this,
            AdsOptions(
                placements = SamplePlacements.all,
                // Short gap so the interstitial can be tried repeatedly by hand.
                fullScreen = FullScreenOptions(minGapAfterFullScreenMs = 15_000L),
                consentDebug = ConsentDebugConfig(ConsentDebugGeography.EEA, listOf("B03100305F5BD40681E0EBC410441D2D")),
                debug = true,
                listener = { EventLog.add(it) },
            ),
        )
    }
}
