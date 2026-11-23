package com.example.adlib.core.fullscreen

import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Why the gate was taken away from its owner (the owner did not release it itself). */
internal enum class ForcedRelease { PREMIUM, WATCHDOG_RESUME, WATCHDOG_TIMEOUT }

/**
 * Who holds the gate: a rewarded waiting screen, a showThen waiting for its ad, an App Open overlay.
 * Compared by identity. [onForcedRelease] runs on the serial dispatcher after the gate is already Idle.
 */
internal class GateOwner(
    val placement: String,
    val onForcedRelease: (ForcedRelease) -> Unit = {},
)

internal sealed interface GateState {
    data object Idle : GateState

    data class Reserved(val owner: GateOwner) : GateState

    data class Showing(val owner: GateOwner) : GateState
}

internal data class FullScreenGateOptions(
    /** Quiet time after ANY full-screen ad closes (v3 6.4, Q-A). Interstitial and App Open check it. */
    val minGapAfterFullScreenMs: Long = 60_000L,
    /** A show with no dismissed/failed callback frees the gate after this at the latest (H5, Q-H). */
    val watchdogMs: Long = 120_000L,
) {
    init {
        require(minGapAfterFullScreenMs >= 0) { "minGapAfterFullScreenMs must be >= 0" }
        require(watchdogMs > 0) { "watchdogMs must be > 0" }
    }
}

/**
 * The one gate for every full-screen ad so two never overlap (v3 6.2). Idle -> Reserved(owner) ->
 * Showing(owner) -> Idle. Call every method on the lib's serial dispatcher ([serialScope], v3 6.7).
 *
 * Watchdog (H5): [show] starts it; if neither [closed] nor [release] comes, the gate is freed on the
 * next ON_RESUME of a non-ad app Activity (counted after the show, so an earlier resume never counts)
 * or after [FullScreenGateOptions.watchdogMs]; emits [AdsEvent.WatchdogFired] and tells the owner.
 * Premium on while Reserved frees the gate and tells the owner; a Showing ad is left to close.
 */
internal class FullScreenGate(
    isPremium: StateFlow<Boolean>,
    private val activityTracker: ActivityTracker,
    private val events: AdsEventBus,
    private val clock: Clock,
    serialScope: CoroutineScope,
    private val options: FullScreenGateOptions = FullScreenGateOptions(),
) {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))
    private val _state = MutableStateFlow<GateState>(GateState.Idle)
    val state: StateFlow<GateState> = _state.asStateFlow()
    private var watchdog: Job? = null
    private var lastClosedAt: Long? = null

    init {
        scope.launch {
            isPremium.collect { premium ->
                val held = _state.value
                if (premium && held is GateState.Reserved) forceRelease(held.owner, ForcedRelease.PREMIUM)
            }
        }
    }

    /** Takes the gate; false when it is not Idle (another full-screen is waiting or showing). */
    fun reserve(owner: GateOwner): Boolean {
        if (_state.value != GateState.Idle) return false
        _state.value = GateState.Reserved(owner)
        return true
    }

    /** Call right before handing the ad to the SDK; false when [owner] no longer holds a reservation. */
    fun show(owner: GateOwner): Boolean {
        if (_state.value != GateState.Reserved(owner)) return false
        _state.value = GateState.Showing(owner)
        val resumesAtShow = activityTracker.appResumes.value
        watchdog = scope.launch {
            val resumed = withTimeoutOrNull(options.watchdogMs) {
                activityTracker.appResumes.first { it > resumesAtShow }
            } != null
            forceRelease(owner, if (resumed) ForcedRelease.WATCHDOG_RESUME else ForcedRelease.WATCHDOG_TIMEOUT)
        }
        return true
    }

    /** The ad was dismissed: frees the gate and starts the gap. No-op unless [owner] holds the gate. */
    fun closed(owner: GateOwner) = free(owner, adClosed = true)

    /** Gives the gate back with no ad closed (no ad, timeout, show failed): no gap. No-op unless [owner] holds it. */
    fun release(owner: GateOwner) = free(owner, adClosed = false)

    /** True within [FullScreenGateOptions.minGapAfterFullScreenMs] after a full-screen ad closed. */
    fun isWithinGap(): Boolean {
        val closedAt = lastClosedAt ?: return false
        val elapsed = clock.nowMs() - closedAt
        return elapsed >= 0 && elapsed < options.minGapAfterFullScreenMs
    }

    private fun free(owner: GateOwner, adClosed: Boolean) {
        when (val held = _state.value) {
            is GateState.Reserved -> if (held.owner !== owner) return
            is GateState.Showing -> {
                if (held.owner !== owner) return
                if (adClosed) lastClosedAt = clock.nowMs()
            }
            GateState.Idle -> return
        }
        toIdle()
    }

    private fun forceRelease(owner: GateOwner, reason: ForcedRelease) {
        if (reason != ForcedRelease.PREMIUM) {
            lastClosedAt = clock.nowMs()
            events.emit(AdsEvent.WatchdogFired(owner.placement, reason.name.lowercase()))
        }
        toIdle()
        owner.onForcedRelease(reason)
    }

    private fun toIdle() {
        watchdog?.cancel()
        watchdog = null
        _state.value = GateState.Idle
    }
}
