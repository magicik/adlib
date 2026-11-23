package com.example.adlib.testing

import android.app.Activity
import android.content.Context
import android.view.View
import com.example.adlib.core.gateway.AdEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdHandle
import com.example.adlib.core.gateway.AdLoadRequest
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.AppOpenHandle
import com.example.adlib.core.gateway.BannerHandle
import com.example.adlib.core.gateway.BannerLoadRequest
import com.example.adlib.core.gateway.FullScreenAdListener
import com.example.adlib.core.gateway.InterstitialHandle
import com.example.adlib.core.gateway.LoadResult
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.gateway.RewardedHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** What the fake does with one load call. Scripted per format with [FakeAdGateway.script]. */
sealed interface FakeBehavior {
    data class Load(val delayMs: Long = 0) : FakeBehavior

    data class Fail(val delayMs: Long = 0, val code: String = "NO_FILL", val message: String = "fake") : FakeBehavior

    /** The SDK never answers (no timeout in the real SDK). */
    data object Never : FakeBehavior

    /** Seeded random delay in [minMs, maxMs]; fails with probability [failRate]. Gives random completion order. */
    data class RandomDelay(val minMs: Long, val maxMs: Long, val failRate: Double = 0.0) : FakeBehavior
}

class FakeAdHandle(
    override val adUnitId: String,
    override val adFormat: AdFormat,
    override val loadedAt: Long,
) : InterstitialHandle, RewardedHandle, AppOpenHandle, NativeHandle, BannerHandle {
    var destroyed = false
        private set
    override val headline: String? = "headline"
    override val body: String? = "body"
    override val callToAction: String? = "cta"
    override val advertiser: String? = "advertiser"
    override val sdkObject: Any = this
    override val view: View? = null

    override fun destroy() {
        destroyed = true
    }
}

/**
 * In-memory [AdGateway]. Like the real one it delivers every callback through [dispatcher].
 * [scope] drives load delays (pass a TestScope for virtual time). With [callbackOnRandomThread]
 * the result first hops to a random background thread, imitating an SDK that calls back from
 * anywhere, before being re-posted to [dispatcher].
 */
class FakeAdGateway(
    private val scope: CoroutineScope,
    private val dispatcher: MainSerialDispatcher,
    private val clock: FakeClock = FakeClock(),
    seed: Long = 0L,
    private val callbackOnRandomThread: Boolean = false,
) : AdGateway {
    private val random = Random(seed)
    private val scripts = mutableMapOf<AdFormat, ArrayDeque<FakeBehavior>>()
    private val lastBehavior = mutableMapOf<AdFormat, FakeBehavior>()
    private val fullScreenListeners = mutableMapOf<AdHandle, FullScreenAdListener>()
    private val eventListeners = mutableMapOf<AdHandle, AdEventListener>()

    /** When set, every full-screen show fails with this code instead of showing. */
    var showFailureCode: String? = null

    /** When set, every full-screen show throws this from the show call itself (no callback follows). */
    var showThrows: RuntimeException? = null

    val loadRequests = mutableListOf<Pair<AdFormat, String>>()
    val shown = mutableListOf<AdHandle>()

    /** The Activity each entry of [shown] was shown on. */
    val shownOn = mutableListOf<Activity>()
    val handles = mutableListOf<FakeAdHandle>()

    /** Behaviors are consumed one per load call; the last one repeats. Default: Load(0). */
    fun script(format: AdFormat, vararg behaviors: FakeBehavior) {
        scripts[format] = ArrayDeque(behaviors.toList())
        lastBehavior.remove(format)
    }

    private fun next(format: AdFormat): FakeBehavior {
        val queue = scripts[format]
        val behavior = queue?.removeFirstOrNull() ?: lastBehavior[format] ?: FakeBehavior.Load()
        lastBehavior[format] = behavior
        return behavior
    }

    private fun <T : AdHandle> load(
        format: AdFormat,
        adUnitId: String,
        callback: (LoadResult<T>) -> Unit,
    ) {
        loadRequests += format to adUnitId
        val behavior = next(format)
        if (behavior is FakeBehavior.Never) return
        scope.launch {
            val result: LoadResult<T> = when (behavior) {
                is FakeBehavior.Load -> { delay(behavior.delayMs); loaded(format, adUnitId) }
                is FakeBehavior.Fail -> { delay(behavior.delayMs); LoadResult.Failed(behavior.code, behavior.message) }
                is FakeBehavior.RandomDelay -> {
                    delay(random.nextLong(behavior.minMs, behavior.maxMs + 1))
                    if (random.nextDouble() < behavior.failRate) LoadResult.Failed("NO_FILL", "fake") else loaded(format, adUnitId)
                }
                FakeBehavior.Never -> return@launch
            }
            if (callbackOnRandomThread) withContext(Dispatchers.Default) { Thread.yield() }
            dispatcher.post { callback(result) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : AdHandle> loaded(format: AdFormat, adUnitId: String): LoadResult<T> {
        val handle = FakeAdHandle(adUnitId, format, clock.nowMs())
        handles += handle
        return LoadResult.Loaded(handle as T)
    }

    override fun loadInterstitial(request: AdLoadRequest, callback: (LoadResult<InterstitialHandle>) -> Unit) =
        load(AdFormat.INTERSTITIAL, request.adUnitId, callback)

    override fun loadRewarded(request: AdLoadRequest, callback: (LoadResult<RewardedHandle>) -> Unit) =
        load(AdFormat.REWARDED, request.adUnitId, callback)

    override fun loadAppOpen(request: AdLoadRequest, callback: (LoadResult<AppOpenHandle>) -> Unit) =
        load(AdFormat.APP_OPEN, request.adUnitId, callback)

    override fun loadNative(request: AdLoadRequest, callback: (LoadResult<NativeHandle>) -> Unit) =
        load(AdFormat.NATIVE, request.adUnitId, callback)

    override fun loadBanner(request: BannerLoadRequest, context: Context, callback: (LoadResult<BannerHandle>) -> Unit) =
        load(AdFormat.BANNER, request.adUnitId, callback)

    private fun fullScreen(handle: AdHandle, activity: Activity, listener: FullScreenAdListener) {
        showThrows?.let { throw it }
        showFailureCode?.let { code ->
            dispatcher.post { listener.onFailedToShow(code, "fake") }
            return
        }
        shown += handle
        shownOn += activity
        fullScreenListeners[handle] = listener
        dispatcher.post { listener.onShowed() }
    }

    override fun showInterstitial(handle: InterstitialHandle, activity: Activity, listener: FullScreenAdListener) =
        fullScreen(handle, activity, listener)

    override fun showRewarded(handle: RewardedHandle, activity: Activity, listener: FullScreenAdListener) =
        fullScreen(handle, activity, listener)

    override fun showAppOpen(handle: AppOpenHandle, activity: Activity, listener: FullScreenAdListener) =
        fullScreen(handle, activity, listener)

    /** When set, [setEventListener] throws this (a misbehaving SDK call). */
    var setEventListenerThrows: RuntimeException? = null

    override fun setEventListener(handle: AdHandle, listener: AdEventListener) {
        setEventListenerThrows?.let { throw it }
        eventListeners[handle] = listener
    }

    // Test drivers: simulate SDK events for an already shown / listened handle.
    fun emitPaid(handle: AdHandle, paid: AdPaid) {
        val listener = fullScreenListeners[handle] ?: eventListeners[handle] ?: return
        dispatcher.post { listener.onPaid(paid) }
    }

    fun click(handle: AdHandle) {
        val listener = fullScreenListeners[handle] ?: eventListeners[handle] ?: return
        dispatcher.post { listener.onClicked() }
    }

    fun dismiss(handle: AdHandle) {
        val listener = fullScreenListeners.remove(handle) ?: return
        dispatcher.post { listener.onDismissed() }
    }

    fun earnReward(handle: AdHandle, type: String = "coins", amount: Int = 1) {
        val listener = fullScreenListeners[handle] ?: return
        dispatcher.post { listener.onUserEarnedReward(type, amount) }
    }
}
