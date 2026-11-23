package com.example.adlib.core.nativead

import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.placement.Placement
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the UI modules (ads-ui-compose, later ads-ui-view) get native ads. Apps do not call it;
 * `Ads.install` provides the instance (T11). Call from the main thread.
 */
interface NativeAdSource {
    /** True -> every slot goes Gone and destroys its ad (R20). */
    val isPremium: StateFlow<Boolean>

    /**
     * Asks for one ad of [placement]. [onResult] runs exactly once, on the lib's serial dispatcher,
     * within a bounded time: an ad (the caller now owns it and must [release] it) or null (not allowed,
     * gone in test mode, hidden by R27b, no fill, timeout, error). A request is never cancelled; a
     * caller that no longer wants the ad releases it.
     */
    fun request(placement: Placement, onResult: (NativeHandle?) -> Unit)

    /** Destroys an ad obtained from [request]. */
    fun release(handle: NativeHandle)
}
