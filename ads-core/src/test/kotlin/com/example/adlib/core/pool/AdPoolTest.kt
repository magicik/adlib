package com.example.adlib.core.pool

import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.HighFloorTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.events.DropReason
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdGateway
import com.example.adlib.core.gateway.AdLoadRequest
import com.example.adlib.core.gateway.InterstitialHandle
import com.example.adlib.core.gateway.LoadResult
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.core.time.Clock
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeAdHandle
import com.example.adlib.testing.FakeBehavior
import com.example.adlib.testing.FakeBehavior.Fail
import com.example.adlib.testing.FakeBehavior.Load
import com.example.adlib.testing.FakeBehavior.Never
import com.example.adlib.testing.FakeRevenueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AdPoolTest {
    private class Env(scope: TestScope, options: PoolOptions, premium: Boolean = false) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val gateway = FakeAdGateway(scope.backgroundScope, serial)
        val events = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(serial, AdsEventListener { events += it })
        var canRequest = true
        var ready = true
        var readyCalls = 0
        val state = AdsState(premium, { null }, bus) { canRequest }
        val testMode = TestMode(FakeRevenueStore(), bus)
        val pool = AdPool(
            gateway, state, testMode, { readyCalls++; ready }, bus,
            Clock { scope.testScheduler.currentTime }, scope.backgroundScope, options,
        )

        fun units() = gateway.loadRequests.map { it.second }

        fun dropped(reason: DropReason) = events.count { it is AdsEvent.Dropped && it.reason == reason }
    }

    private fun TestScope.env(options: PoolOptions = PoolOptions(), premium: Boolean = false) = Env(this, options, premium)

    private fun cfg(vararg normal: Pair<String, Long>, total: Long = 15_000, highFloor: String? = null, gone: Boolean = false) =
        PlacementConfig(
            totalTimeoutMs = total,
            tiers = AdTiers(
                highFloor = HighFloorTier(highFloor.orEmpty(), enable = highFloor != null),
                normal = normal.map { AdUnitTier(it.first, it.second) },
            ),
            goneInTestMode = gone,
        )

    private fun placement(
        name: String = "p",
        format: AdFormat = AdFormat.INTERSTITIAL,
        poolKey: String = name,
        config: PlacementConfig? = cfg("n1" to 10_000),
    ) = Placement(name, format, poolKey, config)

    @Test
    fun `key is poolKey plus format and taking is atomic - two takers never share a handle`() = runTest {
        val e = env()
        val a = placement("a", poolKey = "k")
        val b = placement("b", poolKey = "k")
        val nativeK = placement("c", AdFormat.NATIVE, poolKey = "k")
        e.pool.preload(a)
        runCurrent()
        assertEquals(1, e.gateway.loadRequests.size)

        val first = async { e.pool.take(b, 10_000) }
        val second = async { e.pool.take(a, 10_000) }
        val other = async { e.pool.take(nativeK, 10_000) }
        runCurrent()
        val handles = listOf(first.await(), second.await(), other.await())
        assertTrue(handles.all { it != null })
        assertEquals(3, handles.toSet().size)
        assertSame(e.gateway.handles[0], first.await()) // shared key: b got a's preloaded ad
        assertEquals(AdFormat.NATIVE, other.await()!!.adFormat)
        assertEquals(3, e.gateway.loadRequests.size)
    }

    @Test
    fun `no request and NoAd at once when not ready, not allowed or gone in test mode`() = runTest {
        data class Case(val name: String, val format: AdFormat = AdFormat.INTERSTITIAL, val setup: Env.() -> Unit, val config: PlacementConfig = cfg("n1" to 10_000))
        val cases = listOf(
            Case("bootstrap not ready", setup = { ready = false }),
            Case("no consent", setup = { canRequest = false }),
            Case("premium", setup = { state.setPremium(true) }),
            Case("kill switch", setup = { state.setKillSwitch(true) }),
            Case("placement disabled", setup = {}, config = cfg("n1" to 10_000).copy(enable = false)),
            Case("gone native", AdFormat.NATIVE, { testMode.onNativeHeadline("Test Ad") }, cfg("n1" to 10_000, gone = true)),
            Case("gone interstitial", setup = { testMode.onNativeHeadline("Test Ad") }, config = cfg("n1" to 10_000, gone = true)),
            Case("gone app open", AdFormat.APP_OPEN, { testMode.onNativeHeadline("Test Ad") }, cfg("n1" to 10_000, gone = true)),
        )
        for (case in cases) {
            val e = env()
            case.setup(e)
            val p = placement(format = case.format, config = case.config)
            val start = currentTime
            e.pool.preload(p)
            val result = async { e.pool.take(p, 10_000) }
            runCurrent()
            assertNull(case.name, result.await())
            assertEquals(case.name, start, currentTime)
            assertTrue(case.name, e.gateway.loadRequests.isEmpty())
        }
    }

    @Test
    fun `every load asks the bootstrap first`() = runTest {
        val e = env()
        val p = placement()
        e.pool.preload(p)
        runCurrent()
        val got = async { e.pool.take(p, 10_000) }
        val waited = async { e.pool.take(p, 10_000) }
        runCurrent()
        assertNotNull(got.await())
        assertNotNull(waited.await())
        assertEquals(2, e.gateway.loadRequests.size)
        assertEquals(2, e.readyCalls)
    }

    @Test
    fun `runs min(waiters, parallel) loads at once, not one after another`() = runTest {
        // waiters, parallel
        val cases = listOf(1 to 3, 2 to 3, 8 to 4, 8 to 8)
        for ((waiters, parallel) in cases) {
            val e = env(PoolOptions(maxPerKey = 2, parallelLoadsPerFormat = parallel))
            e.gateway.script(AdFormat.NATIVE, Load(1_000))
            val p = placement(format = AdFormat.NATIVE)
            val start = currentTime
            val takes = List(waiters) { async { e.pool.take(p, 10_000) } }
            runCurrent()
            assertEquals("$waiters/$parallel", minOf(waiters, parallel), e.gateway.loadRequests.size)
            val handles = takes.awaitAll()
            assertTrue(handles.all { it != null })
            assertEquals(waiters, handles.toSet().size)
            val rounds = (waiters + parallel - 1) / parallel
            assertEquals("$waiters/$parallel", start + rounds * 1_000L, currentTime)
            assertEquals(waiters, e.gateway.loadRequests.size)
        }
    }

    @Test
    fun `seeded random delays, failures and never-answering loads - every waiter ends in time, no handle leaks or is shared`() = runTest {
        var adsDelivered = 0
        for (seed in 0 until 40) {
            val random = Random(seed)
            val e = env(PoolOptions(maxPerKey = 2, parallelLoadsPerFormat = 3))
            val script = List(40) {
                val roll = random.nextDouble()
                val delayMs = random.nextLong(0, 8_000)
                when {
                    roll < 0.15 -> Never
                    roll < 0.35 -> Fail(delayMs)
                    else -> Load(delayMs)
                }
            }
            e.gateway.script(AdFormat.NATIVE, *script.toTypedArray<FakeBehavior>())
            val p = placement(format = AdFormat.NATIVE, config = cfg("n1" to 5_000, "n2" to 5_000, total = 8_000))
            val takes = List(8) {
                val startAt = random.nextLong(0, 3_000)
                val timeout = random.nextLong(1_000, 20_000)
                async {
                    delay(startAt)
                    val begin = currentTime
                    val handle = e.pool.take(p, timeout)
                    assertTrue("seed $seed: waiter overran its timeout", currentTime - begin <= timeout)
                    handle
                }
            }
            val handles = takes.awaitAll().filterNotNull()
            assertEquals("seed $seed: a handle was given twice", handles.size, handles.toSet().size)
            assertTrue("seed $seed: delivered a destroyed ad", handles.none { (it as FakeAdHandle).destroyed })
            adsDelivered += handles.size

            advanceTimeBy(60_000) // late answers settle into the pool or get dropped
            e.state.setPremium(true)
            runCurrent()
            for (h in e.gateway.handles) {
                assertTrue("seed $seed: ad neither delivered nor destroyed", (h in handles) != h.destroyed)
            }
        }
        assertTrue(adsDelivered > 0)
    }

    @Test
    fun `waiter timeout or cancel never cancels the load - late result goes to the pool, or is dropped when full`() = runTest {
        val e = env(PoolOptions(maxPerKey = 1, parallelLoadsPerFormat = 2))
        e.gateway.script(AdFormat.INTERSTITIAL, Load(5_000))
        val p = placement()
        val cancelled = launch { e.pool.take(p, 10_000) }
        val timedOut = async { e.pool.take(p, 1_000) }
        advanceTimeBy(500)
        cancelled.cancel()
        assertNull(timedOut.await())
        assertEquals(1_000, currentTime)
        assertEquals(2, e.gateway.loadRequests.size)

        advanceTimeBy(4_001) // both loads answer at 5000
        val (pooled, surplus) = e.gateway.handles
        assertFalse(pooled.destroyed)
        assertTrue(surplus.destroyed)
        assertEquals(1, e.dropped(DropReason.LATE))

        val again = async { e.pool.take(p, 10_000) }
        runCurrent()
        assertSame(pooled, again.await())
        assertEquals(2, e.gateway.loadRequests.size)
    }

    @Test
    fun `normal tiers in order, each with its timeout, all within total_timeout`() = runTest {
        data class Case(val script: List<FakeBehavior>, val units: List<String>, val got: String?, val at: Long)
        val cases = listOf(
            Case(listOf(Load()), listOf("a"), "a", 0),
            Case(listOf(Fail(), Load()), listOf("a", "b"), "b", 0),
            Case(listOf(Never, Load()), listOf("a", "b"), "b", 10_000),
            Case(listOf(Never, Never, Load()), listOf("a", "b"), null, 15_000),
            Case(listOf(Fail(), Fail(), Fail()), listOf("a", "b", "c"), null, 0),
        )
        for (case in cases) {
            val e = env()
            e.gateway.script(AdFormat.INTERSTITIAL, *case.script.toTypedArray())
            val p = placement(config = cfg("a" to 10_000, "b" to 10_000, "c" to 10_000, total = 15_000))
            val start = currentTime
            val handle = e.pool.take(p, 60_000)
            assertEquals("$case", case.units, e.units())
            assertEquals("$case", case.got, handle?.adUnitId)
            assertEquals("$case", start + case.at, currentTime)
        }
    }

    @Test
    fun `high floor only for a background preload with no waiter, never native or reactive`() = runTest {
        // format, preload (else a waiter / reactive load) -> first unit requested
        val cases = listOf(
            Triple(AdFormat.INTERSTITIAL, true, "hf"),
            Triple(AdFormat.REWARDED, true, "hf"),
            Triple(AdFormat.APP_OPEN, true, "hf"),
            Triple(AdFormat.NATIVE, true, "n1"),
            Triple(AdFormat.APP_OPEN, false, "n1"),
            Triple(AdFormat.INTERSTITIAL, false, "n1"),
        )
        for ((format, preload, expected) in cases) {
            val e = env()
            val p = placement(format = format, config = cfg("n1" to 10_000, highFloor = "hf"))
            if (preload) e.pool.preload(p) else launch { e.pool.take(p, 10_000) }
            runCurrent()
            assertEquals("$format preload=$preload", expected, e.units().first())
        }
    }

    @Test
    fun `high floor cap frees the permit after 30s, outside total_timeout, one permit per load`() = runTest {
        val e = env(PoolOptions(parallelLoadsPerFormat = 1))
        e.gateway.script(AdFormat.INTERSTITIAL, Never, Fail(), Never)
        e.pool.preload(placement("a", config = cfg("nA" to 10_000, total = 15_000, highFloor = "hfA")))
        e.pool.preload(placement("b", config = cfg("nB" to 10_000, highFloor = "hfB")))
        runCurrent()
        assertEquals(listOf("hfA"), e.units()) // b waits for the only permit
        advanceTimeBy(29_999)
        assertEquals(listOf("hfA"), e.units())
        advanceTimeBy(1)
        runCurrent()
        // cap: the same load (same permit) goes on to nA although total_timeout is 15s; nA fails,
        // the permit is released in finally, then b starts.
        assertEquals(listOf("hfA", "nA", "hfB"), e.units())
    }

    @Test
    fun `waiter arriving abandons the high floor at once, a late high floor still lands in the pool`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Load(40_000), Load())
        val p = placement(config = cfg("n1" to 10_000, highFloor = "hf"))
        e.pool.preload(p)
        advanceTimeBy(1_000)
        val waiter = async { e.pool.take(p, 10_000) }
        runCurrent()
        assertEquals("n1", waiter.await()?.adUnitId)
        assertEquals(1_000, currentTime)

        advanceTimeBy(40_000)
        val next = async { e.pool.take(p, 10_000) }
        runCurrent()
        assertEquals("hf", next.await()?.adUnitId)
        assertEquals(2, e.gateway.loadRequests.size)
    }

    @Test
    fun `full key - high floor arrival replaces the oldest normal, high floor is taken first`() = runTest {
        val e = env(PoolOptions(maxPerKey = 2))
        // hf answers late (40s); normal at 30s; second preload: hf fails, normal at 31s.
        e.gateway.script(AdFormat.INTERSTITIAL, Load(40_000), Load(), Fail(), Load())
        val p = placement(config = cfg("n1" to 10_000, highFloor = "hf"))
        e.pool.preload(p)
        advanceTimeBy(31_000)
        e.pool.preload(p, count = 2)
        advanceTimeBy(10_000)
        val (oldNormal, newNormal, late) = e.gateway.handles
        assertEquals("hf", late.adUnitId)
        assertTrue(oldNormal.destroyed)
        assertFalse(newNormal.destroyed)
        assertEquals(1, e.dropped(DropReason.CAPPED))

        assertSame(late, e.pool.take(p, 0))
        assertSame(newNormal, e.pool.take(p, 0))
        assertEquals(4, e.gateway.loadRequests.size)
    }

    @Test
    fun `expired entries are destroyed - app open after 4h, others after 55 min`() = runTest {
        val cases = listOf(
            AdFormat.APP_OPEN to 4 * 60 * 60 * 1000L,
            AdFormat.INTERSTITIAL to 55 * 60 * 1000L,
            AdFormat.REWARDED to 55 * 60 * 1000L,
            AdFormat.NATIVE to 55 * 60 * 1000L,
        )
        for ((format, ttl) in cases) {
            val e = env()
            val p = placement(format = format)
            e.pool.preload(p)
            advanceTimeBy(ttl - 1)
            assertSame("$format", e.gateway.handles[0], e.pool.take(p, 10_000))

            e.pool.preload(p)
            advanceTimeBy(ttl)
            val fresh = e.pool.take(p, 10_000)
            runCurrent()
            assertTrue("$format", e.gateway.handles[1].destroyed)
            assertSame("$format", e.gateway.handles[2], fresh)
            assertEquals("$format", 1, e.dropped(DropReason.EXPIRED))
        }
    }

    @Test
    fun `premium on flushes the pool and answers waiters with NoAd`() = runTest {
        val e = env(PoolOptions(maxPerKey = 2))
        val a = placement("a")
        e.pool.preload(a, count = 2)
        runCurrent()
        e.gateway.script(AdFormat.NATIVE, Never)
        val waiter = async { e.pool.take(placement("b", AdFormat.NATIVE), 10_000) }
        runCurrent()

        e.state.setPremium(true)
        runCurrent()
        assertNull(waiter.await())
        assertEquals(0, currentTime)
        assertTrue(e.gateway.handles.all { it.destroyed })
        assertEquals(2, e.dropped(DropReason.FLUSHED))
        assertNull(e.pool.take(a, 10_000))
        assertEquals(3, e.gateway.loadRequests.size)
    }

    @Test
    fun `an ad delivered at the very instant total_timeout fires still reaches its waiter`() = runTest {
        val e = env()
        var callback: ((LoadResult<InterstitialHandle>) -> Unit)? = null
        val gateway = object : AdGateway by e.gateway {
            override fun loadInterstitial(request: AdLoadRequest, callback0: (LoadResult<InterstitialHandle>) -> Unit) {
                callback = callback0
            }
        }
        val pool = AdPool(gateway, e.state, e.testMode, { true }, e.bus, Clock { currentTime }, backgroundScope)
        val handle = FakeAdHandle("n1", AdFormat.INTERSTITIAL, 0)
        // Registered before the pool's total timeout, so at t=1000 it answers first; the timeout then fires
        // (Guards the at-deadline hand-over; with one serial thread the old code also got it through the cancel path.)
        // while the attempt's continuation is already queued (an inline callback, as a test can do).
        launch {
            delay(1_000)
            callback!!(LoadResult.Loaded(handle))
        }
        val got = async { pool.take(placement(config = cfg("n1" to 10_000, total = 1_000)), 10_000) }
        runCurrent()
        advanceTimeBy(1_001)
        assertSame(handle, got.await())
        assertFalse(handle.destroyed)
    }

    @Test
    fun `a throwing gateway ends the load as LoadFailed, waiters get NoAd in time, the permit comes back`() = runTest {
        val e = env(PoolOptions(parallelLoadsPerFormat = 1))
        val p = placement(config = cfg("n1" to 10_000, "n2" to 10_000))
        e.gateway.loadThrows = IllegalStateException("sdk boom")
        assertNull(e.pool.take(p, 10_000))
        runCurrent()
        assertEquals(0, currentTime)
        assertEquals(listOf("n1", "n2"), e.units()) // next tier is still tried
        assertEquals(2, e.events.count { it is AdsEvent.LoadFailed && it.code == AdPool.LOAD_THREW })

        e.gateway.loadThrows = null
        assertNotNull(e.pool.take(p, 10_000)) // the only permit was released, no uncaught exception (runTest fails on one)
    }

    @Test
    fun `ad handed over in the same instant the caller is cancelled is destroyed, not lost`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Load(1_000))
        lateinit var caller: Job
        // Loaded is delivered right after the hand-over; cancelling one task later (after the Loaded event) hits the window between
        // the pool completing take() and the caller reading its result.
        e.bus.listener = AdsEventListener { if (it is AdsEvent.Loaded) e.serial.post { caller.cancel() } }
        caller = launch { e.pool.takeOwned(placement(), 10_000) }
        advanceTimeBy(1_001)
        assertTrue(caller.isCancelled)
        assertEquals(1, e.gateway.handles.size)
        assertTrue(e.gateway.handles.single().destroyed)
    }
}
