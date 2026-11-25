package com.example.adlib.core.fullscreen.interstitial

import android.app.Activity
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.fullscreen.FullScreenGate
import com.example.adlib.core.fullscreen.FullScreenGateOptions
import com.example.adlib.core.fullscreen.GateOwner
import com.example.adlib.core.fullscreen.GateState
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.testing.FakeActivityTracker
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeBehavior.Fail
import com.example.adlib.testing.FakeBehavior.Never
import com.example.adlib.testing.FakeCapStore
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeRevenueStore
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** T20: with default options every wait of showThen ends, and `then` runs once, no later than total_timeout. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowThenTimeoutBoundTest {
    private val placement = Placement(
        "inter", AdFormat.INTERSTITIAL,
        default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000)))),
    )
    private val totalTimeoutMs = PlacementConfig.DEFAULT_TOTAL_TIMEOUT_MS

    private class Case(
        val name: String,
        val consentReady: suspend () -> Boolean = { true },
        val behavior: com.example.adlib.testing.FakeBehavior? = null,
        val busyGate: Boolean = false,
    )

    @Test
    fun `then runs exactly once within total_timeout in every wait branch`() = runTest {
        val cases = listOf(
            Case("ad never arrives", behavior = Never),
            Case("load fails", behavior = Fail()),
            Case("consent never ready", consentReady = { CompletableDeferred<Boolean>().await() }),
            Case("pool empty, consent denied", consentReady = { false }),
            Case("gate busy", busyGate = true),
        )
        for (case in cases) {
            val serial = MainSerialDispatcher(StandardTestDispatcher(testScheduler))
            val clock = FakeClock()
            val bus = AdsEventBus(serial, AdsEventListener { })
            val state = AdsState(false, { null }, bus) { true }
            val testMode = TestMode(FakeRevenueStore(), bus)
            val gateway = FakeAdGateway(backgroundScope, serial, FakeClock())
            case.behavior?.let { gateway.script(AdFormat.INTERSTITIAL, it) }
            val tracker = FakeActivityTracker()
            val pool = AdPool(gateway, state, testMode, case.consentReady, bus, clock, backgroundScope)
            val gate = FullScreenGate(state.isPremium, tracker, bus, clock, backgroundScope, FullScreenGateOptions(minGapAfterFullScreenMs = 0))
            val options = InterstitialOptions(minIntervalMs = 0)
            val controller = InterstitialController(
                pool, gate, gateway, state, testMode,
                InterstitialCaps(options, clock, testMode, FakeCapStore(), TimeZone.getTimeZone("UTC")),
                tracker, bus, clock, backgroundScope, options,
            )
            runCurrent()
            if (case.busyGate) gate.reserve(GateOwner("other"))

            val n = AtomicInteger()
            controller.showThen(mockk<Activity>(relaxed = true), placement) { n.incrementAndGet() }
            advanceTimeBy(totalTimeoutMs); runCurrent()
            assertEquals(case.name, 1, n.get())
            advanceTimeBy(60_000); runCurrent()
            assertEquals(case.name, 1, n.get())
            if (!case.busyGate) assertEquals(case.name, GateState.Idle, gate.state.value)
        }
    }
}
