package com.example.adlib.core.fullscreen.rewarded

import android.app.Activity
import app.cash.turbine.test
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.fullscreen.FullScreenGate
import com.example.adlib.core.fullscreen.FullScreenGateOptions
import com.example.adlib.core.fullscreen.GateOwner
import com.example.adlib.core.fullscreen.GateState
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.testing.FakeActivityTracker
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeBehavior.Fail
import com.example.adlib.testing.FakeBehavior.Load
import com.example.adlib.testing.FakeBehavior.Never
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeRevenueStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RewardedControllerTest {
    private val placement = Placement(
        "reward", AdFormat.REWARDED,
        default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000)))),
    )

    private class Env(val scope: TestScope, gateOptions: FullScreenGateOptions) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val clock = FakeClock()
        val events = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(serial, AdsEventListener { events += it })
        val state = AdsState(false, { null }, bus) { true }
        val testMode = TestMode(FakeRevenueStore(), bus)
        val gateway = FakeAdGateway(scope.backgroundScope, serial, clock)
        val tracker = FakeActivityTracker()
        val pool = AdPool(gateway, state, testMode, { true }, bus, clock, scope.backgroundScope)
        val gate = FullScreenGate(state.isPremium, tracker, bus, clock, scope.backgroundScope, gateOptions)
        val controller = RewardedController(pool, gate, gateway, state, bus, testMode, clock, scope.backgroundScope)
        val activity = mockk<Activity>(relaxed = true)
        val calls = mutableListOf<String>()

        fun request(placement: Placement): Job =
            controller.request(activity, placement, { calls += "earned" }, { calls += "closed" }, { calls += "unavailable" })
    }

    private val noGap = FullScreenGateOptions(minGapAfterFullScreenMs = 0)

    private fun TestScope.env(gate: FullScreenGateOptions = noGap) = Env(this, gate).also { runCurrent() }

    @Test
    fun `exactly one of onUnavailable or (onEarned then onClosed) and the gate is Idle in every branch`() = runTest {
        class Case(val name: String, val expected: List<String>, val shownAds: Int = 0, val body: TestScope.(Env) -> Unit)

        val unavailable = listOf("unavailable")
        val cases = listOf(
            Case("earned then closed", listOf("earned", "closed"), 1) { e ->
                e.controller.preload(placement); runCurrent()
                e.request(placement); runCurrent()
                e.gateway.earnReward(e.gateway.handles[0]); runCurrent()
                e.gateway.dismiss(e.gateway.handles[0])
            },
            Case("closed without reward", listOf("closed"), 1) { e ->
                e.controller.preload(placement); runCurrent()
                e.request(placement); runCurrent()
                e.gateway.dismiss(e.gateway.handles[0])
            },
            Case("no fill", unavailable) { e -> e.gateway.script(AdFormat.REWARDED, Fail()); e.request(placement) },
            Case("timeout 10s, load never answers", unavailable) { e ->
                e.gateway.script(AdFormat.REWARDED, Never)
                e.request(placement); runCurrent()
                advanceTimeBy(9_999); runCurrent()
                assertTrue(e.gate.state.value is GateState.Reserved)
            },
            Case("cancel during a load that later succeeds", unavailable) { e ->
                e.gateway.script(AdFormat.REWARDED, Load(delayMs = 5_000))
                val job = e.request(placement); runCurrent()
                advanceTimeBy(1_000)
                job.cancel(); runCurrent()
                assertEquals(GateState.Idle, e.gate.state.value)
                assertEquals(listOf("unavailable"), e.calls)
            },
            Case("show error callback", unavailable) { e ->
                e.controller.preload(placement); runCurrent()
                e.gateway.showFailureCode = "INTERNAL_ERROR"
                e.request(placement)
            },
            Case("show throws", unavailable) { e ->
                e.controller.preload(placement); runCurrent()
                e.gateway.showThrows = IllegalStateException("fake")
                e.request(placement)
            },
            Case("premium already on", unavailable) { e -> e.state.setPremium(true); e.request(placement) },
            Case("kill switch", unavailable) { e -> e.state.setKillSwitch(true); e.request(placement) },
            Case("premium turns on while waiting", unavailable) { e ->
                e.gateway.script(AdFormat.REWARDED, Never)
                e.request(placement); runCurrent()
                e.state.setPremium(true)
            },
            Case("gate busy", unavailable) { e ->
                val other = GateOwner("other")
                e.gate.reserve(other)
                e.request(placement); runCurrent()
                e.gate.release(other)
            },
            Case("host destroyed while waiting", unavailable) { e ->
                e.gateway.script(AdFormat.REWARDED, Load(delayMs = 1_000))
                e.request(placement); runCurrent()
                every { e.activity.isDestroyed } returns true
            },
            Case("owner cancelled while showing, ad never closes (watchdog)", listOf("closed"), 1) { e ->
                e.controller.preload(placement); runCurrent()
                val job = e.request(placement); runCurrent()
                job.cancel()
                advanceTimeBy(FullScreenGateOptions().watchdogMs)
            },
        )
        for (case in cases) {
            val e = env()
            case.body(this, e)
            advanceTimeBy(130_000); runCurrent()
            assertEquals(case.name, case.expected, e.calls)
            assertEquals(case.name, GateState.Idle, e.gate.state.value)
            assertEquals(case.name, case.shownAds, e.gateway.shown.size)
            e.gateway.handles.filter { it !in e.gateway.shown }.let { unshown ->
                // A handle that arrived but was not shown is destroyed or pooled, never leaked to the gate.
                assertTrue(case.name, unshown.all { it.destroyed || case.name.startsWith("cancel during") })
            }
        }
    }

    @Test
    fun `waiting for the load keeps the gate Reserved`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.REWARDED, Load(delayMs = 2_000))
        e.request(placement); runCurrent()
        assertTrue(e.gate.state.value is GateState.Reserved)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(e.gate.state.value is GateState.Showing)
    }

    @Test
    fun `an expired pooled ad is destroyed and the request is unavailable`() = runTest {
        val e = env()
        e.controller.preload(placement); runCurrent()
        e.gateway.script(AdFormat.REWARDED, Fail())
        e.clock.advanceBy(AdPool.ttlMs(AdFormat.REWARDED) + 1)
        e.request(placement); runCurrent()
        assertEquals(listOf("unavailable"), e.calls)
        assertTrue(e.gateway.handles[0].destroyed)
    }

    @Test
    fun `rewarded ignores the gap and gone_in_test_mode`() = runTest {
        val gonePlacement = Placement(
            "reward2", AdFormat.REWARDED,
            default = PlacementConfig(goneInTestMode = true, tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000)))),
        )
        val e = env(FullScreenGateOptions(minGapAfterFullScreenMs = 60_000))
        e.testMode.onPaid(AdPaid(0, "USD", "UNKNOWN")) // paid events but no revenue = test mode
        assertTrue(e.testMode.isTestMode)
        val other = GateOwner("other")
        e.gate.reserve(other); e.gate.show(other); e.gate.closed(other)
        assertTrue(e.gate.isWithinGap())

        e.request(gonePlacement); runCurrent()
        assertEquals(1, e.gateway.shown.size)
        e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
        assertEquals(listOf("closed"), e.calls)
    }

    @Test
    fun `isAllowed follows premium, kill switch and consent live`() = runTest {
        var consent = true
        val e = env()
        val state = AdsState(false, { null }, e.bus) { consent }
        val controller = RewardedController(e.pool, e.gate, e.gateway, state, e.bus, e.testMode, e.clock, backgroundScope)
        controller.isAllowed(placement).test {
            assertTrue(awaitItem())
            state.setPremium(true); advanceTimeBy(1_000)
            assertFalse(awaitItem())
            state.setPremium(false); advanceTimeBy(1_000)
            assertTrue(awaitItem())
            state.setKillSwitch(true); advanceTimeBy(1_000)
            assertFalse(awaitItem())
            state.setKillSwitch(false); advanceTimeBy(1_000)
            assertTrue(awaitItem())
            consent = false; advanceTimeBy(1_000)
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isAvailable reflects a non-expired pooled ad`() = runTest {
        val e = env()
        e.controller.isAvailable(placement).test {
            assertFalse(awaitItem())
            e.controller.preload(placement); advanceTimeBy(1_000)
            assertTrue(awaitItem())
            e.clock.advanceBy(AdPool.ttlMs(AdFormat.REWARDED) + 1); advanceTimeBy(1_000)
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
