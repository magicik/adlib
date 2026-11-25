package com.example.adlib.core.fullscreen.rewarded

import android.app.Activity
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.DropReason
import com.example.adlib.core.fullscreen.FullScreenGate
import com.example.adlib.core.fullscreen.GateOwner
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdHandle
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.FullScreenAdListener
import com.example.adlib.core.gateway.RewardedHandle
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal data class RewardedOptions(
    /** How long `request` waits for an ad that is not pooled yet (R32); clamped to the placement's `total_timeout_ms`. */
    val waitForAdMs: Long = 10_000L,
    /** Sampling period of the live `isAvailable` / `isAllowed` flows (kill switch, config, expiry have no change signal). */
    val pollMs: Long = 1_000L,
)

/**
 * Rewarded `request` (v3 4, 6.2; R32). Everything runs on the serial scope (v3 6.7).
 *
 * Per request exactly ONE terminal callback: `onUnavailable` (nothing was shown) or `onClosed` (an ad was
 * shown and ended, `onEarned` having fired before it if the user earned the reward). The gate is Reserved
 * while the app shows its waiting screen and released on every path that does not reach `Showing`; a
 * Showing ad ends by its dismiss, its show error (callback or a throwing call) or the gate watchdog. The
 * request ignores `minGapAfterFullScreenMs` (user initiated, as the old lib) and has no caps; `gone_in_test_mode`
 * does not apply (Q-C). Every wait is bounded: the ad wait by [RewardedOptions.waitForAdMs], the shown ad by the watchdog.
 */
internal class RewardedController(
    private val pool: AdPool,
    private val gate: FullScreenGate,
    private val gateway: AdGateway,
    private val state: AdsState,
    private val events: AdsEventBus,
    private val testMode: TestMode,
    private val clock: Clock,
    serialScope: CoroutineScope,
    private val options: RewardedOptions = RewardedOptions(),
) {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))
    private val confined = serialScope.coroutineContext.minusKey(Job)

    /** Background fill of one rewarded ad. */
    fun preload(placement: Placement) {
        requireRewarded(placement)
        pool.preload(placement)
    }

    /** Live: premium, kill switch, canRequestAds and config all considered (sampled every [RewardedOptions.pollMs]). */
    fun isAllowed(placement: Placement): Flow<Boolean> {
        requireRewarded(placement)
        return polled { state.isAdAllowed(placement) }
    }

    /** Live: a non-expired ad is pooled for this placement. */
    fun isAvailable(placement: Placement): Flow<Boolean> {
        requireRewarded(placement)
        return polled { pool.hasFreshAd(placement) }
    }

    private fun polled(read: suspend () -> Boolean): Flow<Boolean> =
        flow {
            while (true) {
                emit(read())
                delay(options.pollMs)
            }
        }.distinctUntilChanged().flowOn(confined)

    /** Returns the flow's Job; cancelling it before the ad shows ends the request with `onUnavailable`. */
    fun request(
        activity: Activity,
        placement: Placement,
        onEarned: () -> Unit,
        onClosed: () -> Unit,
        onUnavailable: () -> Unit,
    ): Job {
        requireRewarded(placement)
        val host = WeakReference(activity)
        val request = Request(onEarned, onClosed, onUnavailable)
        return scope.launch { run(host, placement, request) }
    }

    private suspend fun run(host: WeakReference<Activity>, placement: Placement, request: Request) {
        var taking: Job? = null
        var showing = false
        val owner = GateOwner(placement.name) {
            taking?.cancel()
            // Reserved: the cancelled take reaches the finally below. Showing: nothing else will end it.
            if (showing) request.end()
        }
        try {
            if (!isAlive(host.get())) return
            if (!state.isAdAllowed(placement)) return
            if (!gate.reserve(owner)) return
            val handle =
                pool.takeOwned(placement, minOf(options.waitForAdMs, state.config(placement).totalTimeoutMs)) { taking = it } ?: return

            val activity = host.get()
            val expired = clock.nowMs() - handle.loadedAt >= AdPool.ttlMs(AdFormat.REWARDED)
            if (expired) events.emit(AdsEvent.Dropped(placement.name, DropReason.EXPIRED))
            if (expired || !isAlive(activity) || !state.isAdAllowed(placement) || handle !is RewardedHandle) {
                gateway.destroy(handle)
                return
            }
            if (!gate.show(owner)) {
                gateway.destroy(handle)
                return
            }
            showing = true
            val adListener = listener(owner, placement, handle, request)
            try {
                gateway.showRewarded(handle, activity!!, adListener)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                adListener.onFailedToShow(SHOW_THREW, e.toString())
            }
        } finally {
            if (!showing) {
                gate.release(owner)
                request.end()
            }
        }
    }

    private fun listener(owner: GateOwner, placement: Placement, handle: AdHandle, request: Request) =
        object : FullScreenAdListener {
            override fun onShowed() {
                request.displayed = true
                events.emit(AdsEvent.Shown(placement.name))
            }

            override fun onUserEarnedReward(type: String, amount: Int) = request.earn()

            override fun onDismissed() {
                gate.closed(owner)
                events.emit(AdsEvent.Dismissed(placement.name))
                gateway.destroy(handle)
                request.end()
            }

            override fun onFailedToShow(code: String, message: String) {
                // After onShowed, `displayed` is already true, so end() reports onClosed, not onUnavailable.
                gate.release(owner)
                gateway.destroy(handle)
                request.end()
            }

            override fun onClicked() = events.emit(AdsEvent.Clicked(placement.name))

            override fun onPaid(paid: AdPaid) {
                testMode.onPaid(paid)
                events.emit(AdsEvent.Paid(placement.name, paid))
            }
        }

    private fun isAlive(activity: Activity?): Boolean = activity != null && !activity.isFinishing && !activity.isDestroyed

    private fun requireRewarded(placement: Placement) =
        require(placement.format == AdFormat.REWARDED) { "not a rewarded placement: ${placement.name}" }

    /** The one terminal-callback holder of a request; [end] is idempotent. */
    private class Request(
        private val onEarned: () -> Unit,
        private val onClosed: () -> Unit,
        private val onUnavailable: () -> Unit,
    ) {
        var displayed = false
        private var ended = false

        fun earn() {
            if (!ended) onEarned()
        }

        fun end() {
            if (ended) return
            ended = true
            if (displayed) onClosed() else onUnavailable()
        }
    }

    private companion object {
        const val SHOW_THREW = "SHOW_THREW"
    }
}
