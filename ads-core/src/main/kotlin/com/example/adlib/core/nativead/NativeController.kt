package com.example.adlib.core.nativead

import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.gateway.AdEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdHandle
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Native ads for the UI slots (v3 8). Runs on the serial scope (v3 6.7). A request waits for the
 * pool at most `total_timeout_ms` of the placement; the ad is rechecked when it arrives (allowed,
 * R27b) and anything not handed to the caller is destroyed in one place. The request runs in the
 * lib's scope, not the caller's, so an arrived ad is never lost to a cancellation: a caller that
 * left releases it.
 */
internal class NativeController(
    private val pool: AdPool,
    private val gateway: AdGateway,
    private val state: AdsState,
    private val testMode: TestMode,
    private val events: AdsEventBus,
    serialScope: CoroutineScope,
) : NativeAdSource {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))

    override val isPremium: StateFlow<Boolean> = state.isPremium

    override fun request(placement: Placement, onResult: (NativeHandle?) -> Unit) {
        require(placement.format == AdFormat.NATIVE) { "not a native placement: ${placement.name}" }
        scope.launch {
            var handle: AdHandle? = null
            var result: NativeHandle? = null
            try {
                handle = pool.take(placement, state.config(placement).totalTimeoutMs)
                result = usable(placement, handle)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                events.emit(AdsEvent.LoadFailed(placement.name, null, REQUEST_THREW, e.toString()))
            } finally {
                if (result == null) handle?.let(gateway::destroy)
                onResult(result)
            }
        }
    }

    override fun release(handle: NativeHandle) = gateway.destroy(handle)

    /** The show-time recheck (v3 6.6) and R27b; the listener is set only on an ad that will be shown. */
    private fun usable(placement: Placement, handle: AdHandle?): NativeHandle? {
        if (handle !is NativeHandle) return null
        if (!state.isAdAllowed(placement) || testMode.shouldHideNative(placement, handle.headline))return null
        gateway.setEventListener(handle, listener(placement))
        return handle
    }

    private fun listener(placement: Placement) = object : AdEventListener {
        override fun onImpression() = events.emit(AdsEvent.Shown(placement.name))

        override fun onClicked() = events.emit(AdsEvent.Clicked(placement.name))

        override fun onPaid(paid: AdPaid) {
            testMode.onPaid(paid)
            events.emit(AdsEvent.Paid(placement.name, paid))
        }
    }

    companion object {
        const val REQUEST_THREW = "LIB_ERROR"
    }
}
