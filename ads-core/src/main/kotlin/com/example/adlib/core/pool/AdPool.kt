package com.example.adlib.core.pool

import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.DropReason
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdHandle
import com.example.adlib.core.gateway.AdLoadRequest
import com.example.adlib.core.gateway.LoadResult
import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

internal data class PoolKey(val poolKey: String, val format: AdFormat)

internal enum class AdTier { HIGH_FLOOR, NORMAL }

/** A loaded ad held by the pool. [loadedAt] is from the pool's [Clock]. */
internal class PoolEntry(val handle: AdHandle, val tier: AdTier, val loadedAt: Long, val placement: Placement)

internal data class PoolOptions(
    /** Ads kept per key; a full key starts no preload (P5/P7). */
    val maxPerKey: Int = 2,
    /** Loads running at once per format, shared by all keys of that format (P6). */
    val parallelLoadsPerFormat: Int = 3,
    /** High floor has no SDK timeout; this cap only frees the load permit (v3 6.3, Q11). */
    val highFloorCapMs: Long = 30_000L,
)

/**
 * The self-made pool for interstitial / rewarded / app open / native (v3 6.1, 6.3; banner is not
 * pooled). [serialScope] must be the lib's serial scope (the one MainSerialDispatcher, v3 6.7); all state
 * is touched only there, so there are no locks.
 *
 * - Waiter-driven: a key runs as many loads as it has uncovered waiters, up to the per-format
 *   permit count, so 8 native slots fill in parallel. A load ends in an ad for the OLDEST waiter,
 *   else the pool; a load with no ad answers one waiter with NoAd, so a failing network never spins.
 * - A load: [awaitReadyToLoad] (false -> every waiter of the key gets NoAd) -> fresh config (R8) ->
 *   high floor only for a background preload with no waiter, never Native, capped and abandoned as
 *   soon as a waiter arrives -> normal tiers in order, each with its timeout, all within
 *   `total_timeout_ms` (high floor not counted, Q-B). The permit is the load itself: taken at launch,
 *   released in `finally`, so high floor then normal never takes two.
 * - A result after its attempt gave up (late) still goes to a waiter or the pool; a full key keeps
 *   it only if it is high floor and a normal can make room (oldest normal goes), else it is dropped.
 */
internal class AdPool(
    private val gateway: AdGateway,
    private val state: AdsState,
    private val testMode: TestMode,
    private val awaitReadyToLoad: suspend () -> Boolean,
    private val events: AdsEventBus,
    private val clock: Clock,
    serialScope: CoroutineScope,
    private val options: PoolOptions = PoolOptions(),
) {
    private val scope = CoroutineScope(serialScope.coroutineContext + SupervisorJob(serialScope.coroutineContext[Job]))

    /** The serial dispatcher without its Job: [take] runs there but stays the caller's child. */
    private val confined: CoroutineContext = serialScope.coroutineContext.minusKey(Job)
    private val slots = LinkedHashMap<PoolKey, Slot>()
    private val loadsInFlight = HashMap<AdFormat, Int>()

    private val preloadResultListeners = mutableListOf<(Placement, Boolean) -> Unit>()

    /** Called (serial scope) when a background preload load ends: true = an ad, false = none. Lets a format retry with its own rule. */
    fun addPreloadResultListener(listener: (Placement, Boolean) -> Unit) {
        preloadResultListeners += listener
    }

    init {
        scope.launch { state.isPremium.collect { if (it) flush() } }
    }

    /** Background fill: makes sure up to [count] ads (capped by maxPerKey) are pooled or loading. */
    fun preload(placement: Placement, count: Int = 1) {
        requirePooled(placement)
        scope.launch {
            if (!loadAllowed(placement, state.config(placement))) return@launch
            val slot = slot(placement)
            purgeExpired(slot)
            val target = minOf(count, options.maxPerKey)
            repeat(target - slot.entries.size - slot.inFlight - slot.pendingPreloads.size) {
                slot.pendingPreloads.addLast(placement)
            }
            pump(placement.format)
        }
    }

    /**
     * Takes a pooled ad (atomically, P12) or waits up to [timeoutMs] for one; null = NoAd. Leaving
     * (timeout or cancellation) never cancels the load: its result goes to the next waiter or the pool.
     * Call from the lib's scope; the caller owns (shows or destroys) the returned handle.
     */
    suspend fun take(placement: Placement, timeoutMs: Long): AdHandle? = withContext(confined) {
        requirePooled(placement)
        if (!loadAllowed(placement, state.config(placement))) return@withContext null
        val slot = slot(placement)
        purgeExpired(slot)
        slot.takeBest()?.let { return@withContext it.handle }

        val waiter = Waiter(placement)
        slot.waiters.addLast(waiter)
        slot.abandonHighFloor()
        pump(placement.format)
        try {
            withTimeoutOrNull(timeoutMs) { waiter.answered.await() }
        } catch (e: CancellationException) {
            slot.waiters.remove(waiter)
            waiter.entry?.let { accept(slot, it) } // handed over in the same instant: keep it
            throw e
        }
        slot.waiters.remove(waiter)
        waiter.entry?.handle
    }

    /**
     * [take] as a child of the caller; the ONE place that destroys an ad handed over after the caller was
     * cancelled (nobody will show it). [onTaking] gets the child job so an owner can cancel just the wait.
     * Null = NoAd or the child was cancelled.
     */
    suspend fun takeOwned(placement: Placement, timeoutMs: Long, onTaking: (Job) -> Unit = {}): AdHandle? = coroutineScope {
        val child = async { take(placement, timeoutMs) }
        onTaking(child)
        try {
            child.join()
        } catch (e: CancellationException) {
            child.cancel()
            if (child.isCompleted && !child.isCancelled) child.getCompleted()?.let { gateway.destroy(it) }
            throw e
        }
        if (child.isCancelled) null else child.getCompleted()
    }

    /** True when a non-expired ad is pooled for [placement]'s key; read-only (does not purge or take). */
    suspend fun hasFreshAd(placement: Placement): Boolean = withContext(confined) {
        requirePooled(placement)
        val now = clock.nowMs()
        val ttl = ttlMs(placement.format)
        slots[PoolKey(placement.poolKey, placement.format)]?.entries?.any { now - it.loadedAt < ttl } ?: false
    }

    private fun pump(format: AdFormat) {
        val keys = slots.values.filter { it.key.format == format }
        for (slot in keys) {
            while (freePermits(format) > 0 && slot.waiters.size > slot.inFlight) {
                launchLoad(slot, slot.waiters[slot.inFlight].placement, preload = false)
            }
        }
        for (slot in keys) {
            while (freePermits(format) > 0) {
                val placement = slot.pendingPreloads.removeFirstOrNull() ?: break
                if (slot.entries.size + slot.inFlight < options.maxPerKey) launchLoad(slot, placement, preload = true)
            }
        }
    }

    private fun freePermits(format: AdFormat) = options.parallelLoadsPerFormat - (loadsInFlight[format] ?: 0)

    private fun launchLoad(slot: Slot, placement: Placement, preload: Boolean) {
        val format = slot.key.format
        slot.inFlight++
        loadsInFlight[format] = (loadsInFlight[format] ?: 0) + 1
        scope.launch {
            var entry: PoolEntry? = null
            try {
                entry = runLoad(slot, placement, preload)
            } finally {
                slot.inFlight--
                loadsInFlight[format] = (loadsInFlight[format] ?: 1) - 1
                when {
                    entry != null -> accept(slot, entry)
                    // More waiters than loads still running: the oldest one gets NoAd (no retry loop).
                    slot.waiters.size > slot.inFlight -> answer(slot, slot.waiters.first(), null)
                }
                if (preload) preloadResultListeners.forEach { it(placement, entry != null) }
                pump(format)
            }
        }
    }

    private suspend fun runLoad(slot: Slot, placement: Placement, preload: Boolean): PoolEntry? {
        if (!awaitReadyToLoad()) {
            slot.pendingPreloads.clear()
            slot.waiters.toList().forEach { answer(slot, it, null) }
            return null
        }
        val config = state.config(placement)
        if (!loadAllowed(placement, config)) return null
        val highFloor = config.tiers.highFloor
        val useHighFloor = preload && slot.waiters.isEmpty() && slot.key.format != AdFormat.NATIVE &&
            highFloor.enable && highFloor.adUnit.isNotBlank()
        if (useHighFloor) {
            attempt(slot, placement, highFloor.adUnit, AdTier.HIGH_FLOOR, options.highFloorCapMs)?.let { return it }
        }
        // The entry is kept outside the block: a total timeout firing right after an attempt produced it would
        // make withTimeoutOrNull return null and lose the loaded handle.
        var found: PoolEntry? = null
        withTimeoutOrNull(config.totalTimeoutMs) {
            for (tier in config.enabledAds) {
                found = attempt(slot, placement, tier.adUnit, AdTier.NORMAL, tier.timeoutMs)
                if (found != null) break
            }
        }
        return found
    }

    /** One SDK request. Gives up after [timeoutMs] (or when abandoned); a later answer is handled as late. */
    private suspend fun attempt(slot: Slot, placement: Placement, adUnit: String, tier: AdTier, timeoutMs: Long): PoolEntry? {
        val attempt = Attempt()
        if (tier == AdTier.HIGH_FLOOR) slot.highFloorAttempts += attempt
        try {
            load(slot.key.format, adUnit) { result ->
                if (!attempt.closed) {
                    attempt.result = result
                    attempt.answered.complete(Unit)
                } else if (result is LoadResult.Loaded) {
                    accept(slot, loaded(placement, result.handle, tier))
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A gateway that throws ends this attempt as a failed load, never as a crash.
            attempt.closed = true
            slot.highFloorAttempts -= attempt
            events.emit(AdsEvent.LoadFailed(placement.name, adUnit, LOAD_THREW, e.toString()))
            return null
        }
        try {
            withTimeoutOrNull(timeoutMs) { attempt.answered.await() }
        } catch (e: CancellationException) {
            (attempt.result as? LoadResult.Loaded)?.let { accept(slot, loaded(placement, it.handle, tier)) }
            throw e
        } finally {
            attempt.closed = true
            slot.highFloorAttempts -= attempt
        }
        return when (val result = attempt.result) {
            is LoadResult.Loaded -> loaded(placement, result.handle, tier)
            is LoadResult.Failed -> {
                events.emit(AdsEvent.LoadFailed(placement.name, adUnit, result.code, result.message))
                null
            }
            null -> {
                if (!attempt.abandoned) {
                    events.emit(AdsEvent.LoadFailed(placement.name, adUnit, LIB_TIMEOUT, "no answer in ${timeoutMs}ms"))
                }
                null
            }
        }
    }

    private fun load(format: AdFormat, adUnit: String, callback: (LoadResult<AdHandle>) -> Unit) {
        val request = AdLoadRequest(adUnit)
        when (format) {
            AdFormat.INTERSTITIAL -> gateway.loadInterstitial(request, callback)
            AdFormat.REWARDED -> gateway.loadRewarded(request, callback)
            AdFormat.APP_OPEN -> gateway.loadAppOpen(request, callback)
            AdFormat.NATIVE -> gateway.loadNative(request, callback)
            AdFormat.BANNER -> error("banner is not pooled")
        }
    }

    private fun loaded(placement: Placement, handle: AdHandle, tier: AdTier): PoolEntry {
        events.emit(AdsEvent.Loaded(placement.name, handle.adUnitId))
        if (placement.format == AdFormat.NATIVE) testMode.onNativeHeadline((handle as? NativeHandle)?.headline)
        return PoolEntry(handle, tier, clock.nowMs(), placement)
    }

    /** A loaded ad with no caller yet: oldest waiter, else the pool, else dropped. */
    private fun accept(slot: Slot, entry: PoolEntry) {
        if (state.isPremium.value) return drop(entry, DropReason.FLUSHED)
        slot.waiters.firstOrNull()?.let { return answer(slot, it, entry) }
        purgeExpired(slot)
        if (slot.entries.size < options.maxPerKey) {
            slot.entries += entry
            return
        }
        // Full: drop normal before high floor, older before newer; same rank -> the newcomer is surplus.
        val victim = if (entry.tier == AdTier.HIGH_FLOOR) {
            slot.entries.filter { it.tier == AdTier.NORMAL }.minByOrNull { it.loadedAt }
        } else {
            null
        }
        if (victim == null) return drop(entry, DropReason.LATE)
        slot.entries -= victim
        drop(victim, DropReason.CAPPED)
        slot.entries += entry
    }

    private fun answer(slot: Slot, waiter: Waiter, entry: PoolEntry?) {
        slot.waiters.remove(waiter)
        waiter.entry = entry
        waiter.answered.complete(Unit)
    }

    private fun purgeExpired(slot: Slot) {
        val now = clock.nowMs()
        val ttl = ttlMs(slot.key.format)
        val expired = slot.entries.filter { now - it.loadedAt >= ttl }
        slot.entries -= expired.toSet()
        expired.forEach { drop(it, DropReason.EXPIRED) }
    }

    /** Premium on: destroy everything, answer every waiter with NoAd, forget pending preloads. */
    private fun flush() {
        for (slot in slots.values) {
            slot.entries.toList().forEach { drop(it, DropReason.FLUSHED) }
            slot.entries.clear()
            slot.pendingPreloads.clear()
            slot.waiters.toList().forEach { answer(slot, it, null) }
        }
    }

    private fun drop(entry: PoolEntry, reason: DropReason) {
        gateway.destroy(entry.handle)
        events.emit(AdsEvent.Dropped(entry.placement.name, reason))
    }

    private fun loadAllowed(placement: Placement, config: PlacementConfig): Boolean =
        state.isAdAllowed(config) && !testMode.isGone(placement.format, config)

    private fun slot(placement: Placement) =
        PoolKey(placement.poolKey, placement.format).let { key -> slots.getOrPut(key) { Slot(key) } }

    private fun requirePooled(placement: Placement) =
        require(placement.format != AdFormat.BANNER) { "banner is not pooled: ${placement.name}" }

    private class Waiter(val placement: Placement) {
        val answered = CompletableDeferred<Unit>()
        var entry: PoolEntry? = null
    }

    private class Attempt {
        val answered = CompletableDeferred<Unit>()
        var result: LoadResult<AdHandle>? = null
        var closed = false
        var abandoned = false
    }

    private class Slot(val key: PoolKey) {
        val entries = mutableListOf<PoolEntry>()
        val waiters = ArrayDeque<Waiter>()
        val pendingPreloads = ArrayDeque<Placement>()
        val highFloorAttempts = mutableSetOf<Attempt>()
        var inFlight = 0

        /** High floor first, then the oldest (closest to expiry). */
        fun takeBest(): PoolEntry? =
            entries.minWithOrNull(compareBy<PoolEntry> { it.tier != AdTier.HIGH_FLOOR }.thenBy { it.loadedAt })
                ?.also { entries -= it }

        fun abandonHighFloor() {
            for (attempt in highFloorAttempts) {
                attempt.abandoned = true
                attempt.answered.complete(Unit)
            }
        }
    }

    companion object {
        const val APP_OPEN_TTL_MS = 4 * 60 * 60 * 1000L
        const val DEFAULT_TTL_MS = 55 * 60 * 1000L
        const val LIB_TIMEOUT = "LIB_TIMEOUT"
        const val LOAD_THREW = "LOAD_THREW"

        fun ttlMs(format: AdFormat): Long = if (format == AdFormat.APP_OPEN) APP_OPEN_TTL_MS else DEFAULT_TTL_MS
    }
}
