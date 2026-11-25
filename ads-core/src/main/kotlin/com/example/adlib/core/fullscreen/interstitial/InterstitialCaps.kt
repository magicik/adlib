package com.example.adlib.core.fullscreen.interstitial

import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import java.util.TimeZone

internal data class InterstitialOptions(
    /** Quiet time between two interstitials (R26); test mode raises it to 60s (T2). */
    val minIntervalMs: Long = 50_000L,
    /** Max shows per calendar day; 0 = unlimited. */
    val dailyCap: Int = 0,
    /** Only every Nth attempt may show; 1 = every attempt. */
    val oneInN: Int = 1,
    /** How long showThen waits for an ad that is not pooled yet; clamped to the placement's `total_timeout_ms`. */
    val waitForAdMs: Long = 3_000L,
    /** Delays before the 1st, 2nd... retry of a failed background preload; then it stops until the next preload(). */
    val retryDelaysMs: List<Long> = listOf(5_000L, 15_000L),
) {
    init {
        require(dailyCap >= 0 && oneInN >= 1 && minIntervalMs >= 0 && waitForAdMs > 0) { "invalid interstitial options" }
    }
}

/** Counters that must survive a process restart. */
data class CapSnapshot(
    val dayBucket: Long = -1L,
    val dayCount: Int = 0,
    val lastShownAt: Long? = null,
    val attempts: Int = 0,
)

/** App-provided persistence (SharedPreferences...). [save] runs on the serial dispatcher and must not block. */
interface CapStore {
    fun load(): CapSnapshot

    fun save(snapshot: CapSnapshot)
}

class InMemoryCapStore(private var snapshot: CapSnapshot = CapSnapshot()) : CapStore {
    override fun load() = snapshot

    override fun save(snapshot: CapSnapshot) {
        this.snapshot = snapshot
    }
}

internal enum class CapDecision { ALLOWED, DAILY_CAP, INTERVAL, ONE_IN_N }

/**
 * Interstitial-only caps (v3 6.4; App Open and rewarded are exempt, Q-A): dailyCap, interval, oneInN. Same
 * order as the old lib: day cap, interval, then oneInN, which counts only attempts that passed the other two.
 * The T4 gap is checked separately by the caller. Day boundary = local midnight of [zone] via [Clock].
 */
internal class InterstitialCaps(
    private val options: InterstitialOptions,
    private val clock: Clock,
    private val testMode: TestMode,
    private val store: CapStore,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private var snapshot = store.load()

    /** Checks the caps and, when day cap and interval pass, counts this attempt for oneInN. */
    fun tryConsume(): CapDecision {
        val now = clock.nowMs()
        val s = current(now)
        if (options.dailyCap > 0 && s.dayCount >= options.dailyCap) return CapDecision.DAILY_CAP
        val interval = testMode.effectiveInterstitialIntervalMs(options.minIntervalMs)
        val last = s.lastShownAt
        if (interval > 0 && last != null && now - last in 0 until interval) return CapDecision.INTERVAL
        if (options.oneInN <= 1) return CapDecision.ALLOWED
        val attempts = s.attempts + 1
        save(s.copy(attempts = attempts))
        return if (attempts % options.oneInN == 0) CapDecision.ALLOWED else CapDecision.ONE_IN_N
    }

    /** Call when the ad is really on screen. */
    fun recordShown() {
        val now = clock.nowMs()
        val s = current(now)
        save(s.copy(dayCount = s.dayCount + 1, lastShownAt = now))
    }

    private fun current(now: Long): CapSnapshot {
        val bucket = Math.floorDiv(now + zone.getOffset(now), DAY_MS)
        // Clock moved back (bucket < stored): keep the stored day, so rewinding the clock cannot dodge the cap.
        return if (bucket <= snapshot.dayBucket) snapshot else snapshot.copy(dayBucket = bucket, dayCount = 0)
    }

    private fun save(new: CapSnapshot) {
        snapshot = new
        store.save(new)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
