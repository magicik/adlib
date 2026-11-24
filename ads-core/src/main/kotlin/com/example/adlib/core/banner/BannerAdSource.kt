package com.example.adlib.core.banner

import android.content.Context
import com.example.adlib.core.gateway.BannerHandle
import com.example.adlib.core.placement.Placement
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the UI modules get banners (direct load per View, no pool: v3 0). Apps do not call it;
 * `Ads.install` provides the instance (T11). Call from the main thread.
 */
interface BannerAdSource {
    /** True -> the banner goes Gone and destroys its ad. */
    val isPremium: StateFlow<Boolean>

    /**
     * Loads one banner of [placement] for [widthDp]; [context] must be an Activity context.
     * [onResult] runs exactly once on the lib's serial dispatcher within `total_timeout_ms`: a banner
     * (the caller owns it and must [release] it) or null (not allowed, no fill, timeout, error).
     * A request is never cancelled; a caller that left releases the ad.
     */
    fun request(placement: Placement, widthDp: Int, context: Context, onResult: (BannerHandle?) -> Unit)

    /** Destroys a banner obtained from [request]. */
    fun release(handle: BannerHandle)
}
