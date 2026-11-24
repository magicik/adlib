package com.example.adlib.core.appopen

import android.app.Activity
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.DropReason
import com.example.adlib.core.fullscreen.ActivityTracker
import com.example.adlib.core.fullscreen.ForegroundSource
import com.example.adlib.core.fullscreen.FullScreenGate
import com.example.adlib.core.fullscreen.GateOwner
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdHandle
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.AppOpenHandle
import com.example.adlib.core.gateway.FullScreenAdListener
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AppOpenResumeOptions(
    /** A return counts only after this long in the background (Q-D: 0 like the old lib). */
    val minBackgroundMs: Long = 0L,
    /** The first return's ad must arrive within this to be shown (old AppOpenResumeHelper: 5s); later it stays cached. */
    val reactiveShowTimeoutMs: Long = DEFAULT_REACTIVE_SHOW_TIMEOUT_MS,
    /** Hard cap of the overlay (R2/R3); at most [MAX_OVERLAY_MS], never infinite. */
    val overlayMaxMs: Long = DEFAULT_REACTIVE_SHOW_TIMEOUT_MS,
    /** No App Open while one of these (or a subclass) is the top Activity: lock/PIN, onboarding, dialogs. */
    val excludedActivities: Set<Class<out Activity>> = emptySet(),
) {
    init {
        require(minBackgroundMs >= 0) { "minBackgroundMs must be >= 0" }
        require(reactiveShowTimeoutMs > 0) { "reactiveShowTimeoutMs must be > 0" }
        require(overlayMaxMs in 1..MAX_OVERLAY_MS) { "overlayMaxMs must be in 1..$MAX_OVERLAY_MS" }
    }

    /** One window per return: waiting for the host, the overlay and the reactive ad all end within it. */
    val windowMs: Long get() = minOf(reactiveShowTimeoutMs, overlayMaxMs)

    companion object {
        const val DEFAULT_REACTIVE_SHOW_TIMEOUT_MS = 5_000L
        const val MAX_OVERLAY_MS = 15_000L
        const val DEFAULT_PAUSE_MS = 60_000L
    }
}

/**
 * App Open when the user RETURNS to the app (R33, v3 7). Everything runs on the serial scope (v3 6.7).
 *
 * - The first foreground after [attachResume] is the launch, not a return.
 * - A return is skipped after an ad click (any [AdsEvent.Clicked]), while a consent/privacy form shows,
 *   within a [pauseForExternalFlow] (one return, expires by itself), when the app was away less than
 *   `minBackgroundMs`, when ads are not allowed, the gate is busy or within the full-screen gap, or when
 *   the top Activity is excluded. A skip changes nothing else.
 * - Otherwise, once the top Activity is RESUMED (a show at ON_START would be followed by the host's
 *   onResume, which the gate watchdog counts as "ad gone"): a cached ad is shown; else the first try of
 *   the process loads one with the overlay up and shows it if it arrives within the window (late = cached
 *   for next time); later tries never block, they only preload silently. Armed after the first try,
 *   whatever its result (Q-E). Right after handing an ad to the SDK one next ad is preloaded.
 * - The gate is Reserved from the decision until the show; every other exit (no ad, timeout, background,
 *   Premium, host gone) releases it and hides the overlay in one `finally`. A Showing ad is freed by its
 *   dismiss, its show error (callback or throwing call) or the gate watchdog.
 */
internal class AppOpenResumeController(
    private val placement: Placement,
    private val pool: AdPool,
    private val gate: FullScreenGate,
    private val gateway: AdGateway,
    private val state: AdsState,
    private val testMode: TestMode,
    private val tracker: ActivityTracker,
    private val foreground: ForegroundSource,
    private val formShowing: StateFlow<Boolean>,
    private val overlay: AppOpenOverlay,
    private val events: AdsEventBus,
    private val clock: Clock,
    serialScope: CoroutineScope,
    private val options: AppOpenResumeOptions = AppOpenResumeOptions(),
) {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))
    private var attached = false
    private var armed = false
    private var clickedSinceReturn = false
    private var pausedUntil: Long? = null
    private var backgroundAt: Long? = null
    private var attempt: Job? = null

    init {
        require(placement.format == AdFormat.APP_OPEN) { "not an app open placement: ${placement.name}" }
    }

    /** Starts watching returns; a second call does nothing. */
    fun attachResume() {
        scope.launch {
            if (attached) return@launch
            attached = true
            events.observe { if (it is AdsEvent.Clicked) clickedSinceReturn = true }
            var launched = false
            foreground.isForeground.collect { isForeground ->
                when {
                    !isForeground -> onBackground()
                    launched -> onReturn()
                    else -> launched = true
                }
            }
        }
    }

    /** Skips the next return if it comes within [maxMs] (share sheet, SAF picker, billing...); expires by itself. */
    fun pauseForExternalFlow(maxMs: Long = AppOpenResumeOptions.DEFAULT_PAUSE_MS) {
        require(maxMs > 0) { "maxMs must be > 0" }
        scope.launch { pausedUntil = clock.nowMs() + maxMs }
    }

    private fun onBackground() {
        backgroundAt = clock.nowMs()
        attempt?.cancel()
        attempt = null
    }

    private fun onReturn() {
        val now = clock.nowMs()
        val awayMs = now - (backgroundAt ?: now)
        val clicked = clickedSinceReturn
        val paused = pausedUntil?.let { now <= it } ?: false
        // One-shot: whatever happens, they only ever cover this return.
        clickedSinceReturn = false
        pausedUntil = null
        if (clicked || paused || formShowing.value || awayMs < options.minBackgroundMs) return
        attempt?.cancel()
        attempt = scope.launch { tryShow() }
    }

    private suspend fun tryShow() {
        val job = currentCoroutineContext()[Job]
        var showing = false
        // Premium while Reserved ends the wait (the finally hides the overlay); a watchdog while Showing needs nothing.
        val owner = GateOwner(placement.name) { if (!showing) job?.cancel() }
        var cover: AppOpenOverlay.Shown? = null
        try {
            withTimeoutOrNull(options.windowMs) {
                val host = awaitResumedHost()
                // No suspension between the check and reserve: Premium already on is seen here.
                if (!canShow(host) || !gate.reserve(owner)) return@withTimeoutOrNull
                val firstTry = !armed
                armed = true
                val handle = when {
                    pool.hasFreshAd(placement) -> pool.takeOwned(placement, 0L)
                    firstTry -> {
                        try {
                            cover = overlay.show(host)
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            // A broken app overlay must not crash or hold the gate: report, keep the ad coming for next time.
                            events.emit(AdsEvent.LoadFailed(placement.name, null, OVERLAY_THREW, e.toString()))
                            pool.preload(placement)
                            return@withTimeoutOrNull
                        }
                        pool.takeOwned(placement, Long.MAX_VALUE) // bounded by the window
                    }
                    else -> {
                        pool.preload(placement)
                        null
                    }
                } ?: return@withTimeoutOrNull
                showing = show(owner, handle)
            }
        } finally {
            runCatching { cover?.hide() } // a throwing hide must not keep the gate
            if (!showing) gate.release(owner)
        }
    }

    /** Returns at once when the top Activity is already resumed (its onResume is behind us). */
    private suspend fun awaitResumedHost(): Activity {
        tracker.appResumes.first { tracker.resumedActivity != null }
        return checkNotNull(tracker.resumedActivity)
    }

    /**
     * Recheck at show time on the CURRENT top Activity (v3 6.6, M7). True = handed to the SDK (Showing) and
     * one next ad preloaded; false = nothing shown, handle destroyed.
     */
    private fun show(owner: GateOwner, handle: AdHandle): Boolean {
        val host = tracker.resumedActivity
        val expired = clock.nowMs() - handle.loadedAt >= AdPool.ttlMs(AdFormat.APP_OPEN)
        if (expired) events.emit(AdsEvent.Dropped(placement.name, DropReason.EXPIRED))
        if (expired || host == null || !foreground.isForeground.value || !canShow(host) ||
            handle !is AppOpenHandle || !gate.show(owner)
        ) {
            gateway.destroy(handle)
            return false
        }
        val listener = listener(owner, handle)
        try {
            gateway.showAppOpen(handle, host, listener)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A throwing show ends like a failed show: same single cleanup.
            listener.onFailedToShow(SHOW_THREW, e.toString())
        }
        pool.preload(placement)
        return true
    }

    private fun canShow(host: Activity): Boolean {
        if (options.excludedActivities.any { it.isInstance(host) }) return false
        if (formShowing.value || gate.isWithinGap()) return false
        val config = state.config(placement)
        return state.isAdAllowed(config) && !testMode.isGone(AdFormat.APP_OPEN, config)
    }

    /** Frees the gate on dismiss / show error (no-op if the watchdog already did) and destroys the ad. */
    private fun listener(owner: GateOwner, handle: AdHandle) = object : FullScreenAdListener {
        override fun onShowed() = events.emit(AdsEvent.Shown(placement.name))

        override fun onDismissed() {
            gate.closed(owner)
            events.emit(AdsEvent.Dismissed(placement.name))
            gateway.destroy(handle)
        }

        override fun onFailedToShow(code: String, message: String) {
            gate.release(owner)
            gateway.destroy(handle)
        }

        override fun onClicked() = events.emit(AdsEvent.Clicked(placement.name))

        override fun onPaid(paid: AdPaid) {
            testMode.onPaid(paid)
            events.emit(AdsEvent.Paid(placement.name, paid))
        }
    }

    private companion object {
        const val SHOW_THREW = "SHOW_THREW"
        const val OVERLAY_THREW = "OVERLAY_THREW"
    }
}
