package com.example.adlib.core.fullscreen

import android.app.Activity
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.fullscreen.interstitial.InterstitialCaps
import com.example.adlib.core.fullscreen.interstitial.InterstitialController
import com.example.adlib.core.fullscreen.interstitial.InterstitialOptions
import com.example.adlib.core.fullscreen.rewarded.RewardedController
import com.example.adlib.core.fullscreen.rewarded.RewardedOptions
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.testing.FakeActivityTracker
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeCapStore
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeRevenueStore
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** T23: the host wait is min(waitForAdMs, total_timeout), so `then` / onUnavailable never run later than total_timeout. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitForAdClampTest {
    private val totalMs = 5_000L
    private val config = PlacementConfig(totalTimeoutMs = totalMs, tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000))))
    private val interstitial = Placement("inter", AdFormat.INTERSTITIAL, default = config)
    private val rewarded = Placement("rew", AdFormat.REWARDED, default = config)

    @Test
    fun `interstitial then runs by total_timeout even when waitForAdMs is larger`() = runTest {
        val serial = MainSerialDispatcher(StandardTestDispatcher(testScheduler))
        val clock = FakeClock()
        val bus = AdsEventBus(serial, AdsEventListener { })
        val state = AdsState(false, { null }, bus) { true }
        val testMode = TestMode(FakeRevenueStore(), bus)
        val gateway = FakeAdGateway(backgroundScope, serial, clock)
        val tracker = FakeActivityTracker()
        val pool = AdPool(gateway, state, testMode, { CompletableDeferred<Boolean>().await() }, bus, clock, backgroundScope)
        val gate = FullScreenGate(state.isPremium, tracker, bus, clock, backgroundScope, FullScreenGateOptions(minGapAfterFullScreenMs = 0))
        val options = InterstitialOptions(minIntervalMs = 0, waitForAdMs = 60_000)
        val controller = InterstitialController(
            pool, gate, gateway, state, testMode,
            InterstitialCaps(options, clock, testMode, FakeCapStore(), TimeZone.getTimeZone("UTC")),
            tracker, bus, clock, backgroundScope, options,
        )
        runCurrent()
        val n = AtomicInteger()
        controller.showThen(mockk<Activity>(relaxed = true), interstitial) { n.incrementAndGet() }
        advanceTimeBy(totalMs + 1); runCurrent()
        assertEquals(1, n.get())
    }

    @Test
    fun `rewarded onUnavailable runs by total_timeout even when waitForAdMs is larger`() = runTest {
        val serial = MainSerialDispatcher(StandardTestDispatcher(testScheduler))
        val clock = FakeClock()
        val bus = AdsEventBus(serial, AdsEventListener { })
        val state = AdsState(false, { null }, bus) { true }
        val testMode = TestMode(FakeRevenueStore(), bus)
        val gateway = FakeAdGateway(backgroundScope, serial, clock)
        val tracker = FakeActivityTracker()
        val pool = AdPool(gateway, state, testMode, { CompletableDeferred<Boolean>().await() }, bus, clock, backgroundScope)
        val gate = FullScreenGate(state.isPremium, tracker, bus, clock, backgroundScope, FullScreenGateOptions(minGapAfterFullScreenMs = 0))
        val controller = RewardedController(
            pool, gate, gateway, state, bus, testMode, clock, backgroundScope, RewardedOptions(waitForAdMs = 60_000),
        )
        runCurrent()
        val n = AtomicInteger()
        controller.request(mockk<Activity>(relaxed = true), rewarded, { }, { }, { n.incrementAndGet() })
        advanceTimeBy(totalMs + 1); runCurrent()
        assertEquals(1, n.get())
    }
}
