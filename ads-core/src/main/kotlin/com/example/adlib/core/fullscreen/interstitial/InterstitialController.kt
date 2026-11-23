package com.example.adlib.core.fullscreen.interstitial

import android.app.Activity
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.DropReason
import com.example.adlib.core.fullscreen.ActivityTracker
import com.example.adlib.core.fullscreen.FullScreenGate
import com.example.adlib.core.fullscreen.GateOwner
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdHandle
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.FullScreenAdListener
import com.example.adlib.core.gateway.InterstitialHandle
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Interstitial preload + `showThen` (v3 4, 6.2, 6.4, 6.6). Everything runs on the serial scope (v3 6.7).
 *
 * `showThen` ALWAYS runs its action exactly once, after the ad closed or whenever no ad is shown (no ad,
 * load error, show error, capped, gap, timeout, gate busy, premium, kill switch, gone in test mode). The
 * action never runs on a dead host: if the Activity was destroyed (recreate) or the app is in the
 * background it waits until an app Activity is STARTED. That wait has no timeout on purpose (the action
 * must not be dropped and must not run on a dead host); it holds no gate and no Activity.
 *
 * The gate is reserved before waiting for the ad and given back on every path that does not reach
 * `Showing` (the gate has no Reserved timeout by design); a Showing ad is freed by its dismiss, the show
 * error, or the gate watchdog.
 */
internal class InterstitialController(
    private val pool: AdPool,
    private val gate: FullScreenGate,
    private val gateway: AdGateway,
    private val state: AdsState,
    private val testMode: TestMode,
    private val caps: InterstitialCaps,
    private val tracker: ActivityTracker,
    private val events: AdsEventBus,
    private val clock: Clock,
    serialScope: CoroutineScope,
    private val options: InterstitialOptions = InterstitialOptions(),
) {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))
    private val preloadFailures = HashMap<String, Int>()

    init {
        pool.addPreloadFailedListener(::onPreloadFailed)
    }

    /** Background fill of one interstitial; a failed load is retried after 5s then 15s, then waits for the next call. */
    fun preload(placement: Placement) {
        requireInterstitial(placement)
        scope.launch {
            preloadFailures.remove(placement.name)
            pool.preload(placement)
        }
    }

    /** Returns the flow's Job; cancelling it still runs [action] once. */
    fun showThen(activity: Activity, placement: Placement, action: () -> Unit): Job {
        requireInterstitial(placement)
        val host = WeakReference(activity)
        return scope.launch {
            try {
                tryShow(host, placement)
            } finally {
                withContext(NonCancellable) {
                    awaitLiveHost(host)
                    action()
                }
            }
        }
    }

    private fun onPreloadFailed(placement: Placement) {
        if (placement.format != AdFormat.INTERSTITIAL) return
        val failures = preloadFailures[placement.name] ?: 0
        val wait = options.retryDelaysMs.getOrNull(failures) ?: return
        preloadFailures[placement.name] = failures + 1
        scope.launch {
            delay(wait)
            pool.preload(placement)
        }
    }

    /** Returns when the ad was closed or no ad was shown; the caller then runs the action. */
    private suspend fun tryShow(host: WeakReference<Activity>, placement: Placement) {
        if (!isAlive(host.get())) return
        if (!isAllowed(placement)) return
        if (gate.isWithinGap()) return
        if (caps.tryConsume() != CapDecision.ALLOWED) return

        var taking: Job? = null
        val ended = CompletableDeferred<Unit>()
        val owner = GateOwner(placement.name) {
            taking?.cancel()
            ended.complete(Unit)
        }
        if (!gate.reserve(owner)) return
        var showing = false
        try {
            val take = scope.async { pool.take(placement, options.waitForAdMs) }
            taking = take
            take.join()
            val handle = if (take.isCancelled) null else take.getCompleted()
            if (handle == null) return
            preloadFailures.remove(placement.name)

            val activity = host.get()
            val expired = clock.nowMs() - handle.loadedAt >= AdPool.ttlMs(AdFormat.INTERSTITIAL)
            if (expired) events.emit(AdsEvent.Dropped(placement.name, DropReason.EXPIRED))
            if (expired || !isAlive(activity) || !isAllowed(placement) || handle !is InterstitialHandle) {
                gateway.destroy(handle)
                return
            }
            if (!gate.show(owner)) {
                gateway.destroy(handle)
                return
            }
            showing = true
            try {
                gateway.showInterstitial(handle, activity!!, listener(owner, placement, handle, ended))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                gate.release(owner)
                gateway.destroy(handle)
                showing = false
                return
            }
            ended.await()
        } finally {
            if (!showing) gate.release(owner)
        }
    }

    private fun listener(owner: GateOwner, placement: Placement, handle: AdHandle, ended: CompletableDeferred<Unit>) =
        object : FullScreenAdListener {
            override fun onShowed() {
                caps.recordShown()
                events.emit(AdsEvent.Shown(placement.name))
            }

            override fun onDismissed() {
                gate.closed(owner)
                events.emit(AdsEvent.Dismissed(placement.name))
                gateway.destroy(handle)
                ended.complete(Unit)
            }

            override fun onFailedToShow(code: String, message: String) {
                gate.release(owner)
                gateway.destroy(handle)
                ended.complete(Unit)
            }

            override fun onClicked() = events.emit(AdsEvent.Clicked(placement.name))

            override fun onPaid(paid: AdPaid) {
                testMode.onPaid(paid)
                events.emit(AdsEvent.Paid(placement.name, paid))
            }
        }

    /** The one recheck used before the flow starts and again right before show (v3 6.6). */
    private fun isAllowed(placement: Placement): Boolean {
        val config = state.config(placement)
        return state.isAdAllowed(config) && !testMode.isGone(placement.format, config)
    }

    private fun isAlive(activity: Activity?): Boolean = activity != null && !activity.isFinishing && !activity.isDestroyed

    private suspend fun awaitLiveHost(host: WeakReference<Activity>) {
        if (!isAlive(host.get()) || tracker.startedAppActivities.value == 0) {
            tracker.startedAppActivities.first { it > 0 }
        }
    }

    private fun requireInterstitial(placement: Placement) =
        require(placement.format == AdFormat.INTERSTITIAL) { "not an interstitial placement: ${placement.name}" }
}
