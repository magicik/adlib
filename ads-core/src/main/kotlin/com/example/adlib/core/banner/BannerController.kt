package com.example.adlib.core.banner

import android.content.Context
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.gateway.AdEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.BannerHandle
import com.example.adlib.core.gateway.BannerLoadRequest
import com.example.adlib.core.gateway.LoadResult
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Banner loads (v3 0: direct, no pool). Runs on the serial scope. A request: `awaitReadyToLoad` ->
 * fresh config -> normal tiers in order, each within its own timeout, all within `total_timeout_ms`.
 * A result that arrives after its timeout (or after cancellation) is destroyed; anything not handed
 * to the caller is destroyed in one `finally`. Paid/click/impression feed [TestMode] and the event bus
 * (a click also reaches App Open's "skip next resume" through the bus).
 */
internal class BannerController(
    private val gateway: AdGateway,
    private val state: AdsState,
    private val testMode: TestMode,
    private val events: AdsEventBus,
    private val awaitReadyToLoad: suspend () -> Boolean,
    serialScope: CoroutineScope,
) : BannerAdSource {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))

    override val isPremium: StateFlow<Boolean> = state.isPremium

    override fun request(placement: Placement, widthDp: Int, context: Context, onResult: (BannerHandle?) -> Unit) {
        require(placement.format == AdFormat.BANNER) { "not a banner placement: ${placement.name}" }
        scope.launch {
            var result: BannerHandle? = null
            try {
                result = load(placement, widthDp, context)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                events.emit(AdsEvent.LoadFailed(placement.name, null, REQUEST_THREW, e.toString()))
            } finally {
                onResult(result)
            }
        }
    }

    override fun release(handle: BannerHandle) = gateway.destroy(handle)

    private suspend fun load(placement: Placement, widthDp: Int, context: Context): BannerHandle? {
        if (!awaitReadyToLoad()) return null
        val config = state.config(placement)
        if (!state.isAdAllowed(config)) return null
        val handle = withTimeoutOrNull(config.totalTimeoutMs) {
            for (tier in config.enabledAds) {
                attempt(placement, tier.adUnit, widthDp, context, tier.timeoutMs)?.let { return@withTimeoutOrNull it }
            }
            null
        } ?: return null
        if (!state.isAdAllowed(placement)) {
            gateway.destroy(handle)
            return null
        }
        gateway.setEventListener(handle, listener(placement))
        return handle
    }

    private suspend fun attempt(
        placement: Placement,
        adUnit: String,
        widthDp: Int,
        context: Context,
        timeoutMs: Long,
    ): BannerHandle? {
        val answer = CompletableDeferred<LoadResult<BannerHandle>>()
        var handedOver = false
        try {
            gateway.loadBanner(BannerLoadRequest(adUnit, widthDp), context) { answer.complete(it) }
            val result = withTimeoutOrNull(timeoutMs) { answer.await() }
            return when (result) {
                is LoadResult.Loaded -> result.handle.also { handedOver = true; events.emit(AdsEvent.Loaded(placement.name, adUnit)) }
                is LoadResult.Failed -> {
                    events.emit(AdsEvent.LoadFailed(placement.name, adUnit, result.code, result.message))
                    null
                }
                null -> null
            }
        } finally {
            if (!handedOver) {
                answer.invokeOnCompletion {
                    (answer.getCompleted() as? LoadResult.Loaded)?.handle?.let(gateway::destroy)
                }
            }
        }
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
