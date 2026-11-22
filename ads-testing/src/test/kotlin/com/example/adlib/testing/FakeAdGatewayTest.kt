package com.example.adlib.testing

import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdLoadRequest
import com.example.adlib.core.gateway.LoadResult
import com.example.adlib.core.gateway.MainSerialDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeAdGatewayTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val clock = FakeClock(1_000)
    private val dispatcher = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))

    private fun gateway(seed: Long = 1L) = FakeAdGateway(scope, dispatcher, clock, seed)

    private fun FakeAdGateway.loadInter(results: MutableList<LoadResult<*>>, tag: Int = 0) =
        loadInterstitial(AdLoadRequest("unit$tag")) { results += it }

    @Test
    fun `scripted behaviors load, fail, delay and never answer`() {
        val gw = gateway()
        gw.script(
            AdFormat.INTERSTITIAL,
            FakeBehavior.Load(),
            FakeBehavior.Fail(code = "TIMEOUT"),
            FakeBehavior.Load(delayMs = 100),
            FakeBehavior.Never,
        )
        val results = mutableListOf<LoadResult<*>>()
        repeat(4) { gw.loadInter(results, it) }

        scope.testScheduler.runCurrent()
        assertEquals(2, results.size)
        val loaded = results[0] as LoadResult.Loaded<*>
        assertEquals(1_000L, loaded.handle.loadedAt)
        assertEquals(AdFormat.INTERSTITIAL, loaded.handle.adFormat)
        assertEquals("TIMEOUT", (results[1] as LoadResult.Failed).code)

        scope.testScheduler.advanceTimeBy(100)
        scope.testScheduler.runCurrent()
        assertEquals(3, results.size)

        scope.testScheduler.advanceTimeBy(1_000_000)
        scope.testScheduler.runCurrent()
        assertEquals(3, results.size) // the 4th never answers
    }

    @Test
    fun `same seed gives the same random completion order`() {
        fun order(seed: Long): List<String> {
            val localScope = TestScope(StandardTestDispatcher())
            val d = MainSerialDispatcher(StandardTestDispatcher(localScope.testScheduler))
            val gw = FakeAdGateway(localScope, d, FakeClock(), seed)
            gw.script(AdFormat.INTERSTITIAL, FakeBehavior.RandomDelay(0, 1_000))
            val done = mutableListOf<String>()
            repeat(8) { i ->
                gw.loadInterstitial(AdLoadRequest("u$i")) { r -> done += (r as LoadResult.Loaded).handle.adUnitId }
            }
            localScope.testScheduler.advanceTimeBy(2_000)
            localScope.testScheduler.runCurrent()
            return done
        }

        assertEquals(order(42), order(42))
        assertEquals(8, order(42).size)
        assertTrue(order(42) != order(7))
    }
}
