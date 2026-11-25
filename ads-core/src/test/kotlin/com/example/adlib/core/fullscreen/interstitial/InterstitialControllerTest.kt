package com.example.adlib.core.fullscreen.interstitial

import android.app.Activity
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.events.DropReason
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
import com.example.adlib.core.time.Clock
import com.example.adlib.testing.FakeActivityTracker
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeBehavior.Fail
import com.example.adlib.testing.FakeBehavior.Load
import com.example.adlib.testing.FakeBehavior.Never
import com.example.adlib.testing.FakeCapStore
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeRevenueStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class InterstitialControllerTest {
    private val placement = Placement(
        "inter",
        AdFormat.INTERSTITIAL,
        default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000)))),
    )

    private class Env(
        val scope: TestScope,
        options: InterstitialOptions,
        gateOptions: FullScreenGateOptions,
        gatewayClock: FakeClock,
    ) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val clock = FakeClock()
        val events = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(serial, AdsEventListener { events += it })
        val state = AdsState(false, { null }, bus) { true }
        val testMode = TestMode(FakeRevenueStore(), bus)
        val gateway = FakeAdGateway(scope.backgroundScope, serial, gatewayClock)
        val tracker = FakeActivityTracker()
        val pool = AdPool(gateway, state, testMode, { true }, bus, clock, scope.backgroundScope)
        val gate = FullScreenGate(state.isPremium, tracker, bus, clock, scope.backgroundScope, gateOptions)
        val controller = InterstitialController(
            pool, gate, gateway, state, testMode,
            InterstitialCaps(options, clock, testMode, FakeCapStore(), TimeZone.getTimeZone("UTC")),
            tracker, bus, clock, scope.backgroundScope, options,
        )
        val activity = mockk<Activity>(relaxed = true)

        fun show(placement: Placement): AtomicInteger {
            val n = AtomicInteger()
            controller.showThen(activity, placement) { n.incrementAndGet() }
            return n
        }

        fun interRequests() = gateway.loadRequests.count { it.first == AdFormat.INTERSTITIAL }
    }

    private val openOptions = InterstitialOptions(minIntervalMs = 0)
    private val noGap = FullScreenGateOptions(minGapAfterFullScreenMs = 0)

    private fun TestScope.env(
        options: InterstitialOptions = openOptions,
        gate: FullScreenGateOptions = noGap,
        gatewayClock: FakeClock = FakeClock(),
    ) = Env(this, options, gate, gatewayClock).also { runCurrent() }

    @Test
    fun `action runs exactly once and the gate is Idle afterwards in every branch`() = runTest {
        class Case(
            val name: String,
            val shownAds: Int = 0,
            val options: InterstitialOptions = openOptions,
            val gate: FullScreenGateOptions = noGap,
            val body: TestScope.(Env) -> AtomicInteger,
        )

        val cases = listOf(
            Case("shown then dismissed", shownAds = 1) { e ->
                e.controller.preload(placement); runCurrent()
                val n = e.show(placement); runCurrent()
                e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
                n
            },
            Case("no ad (no fill)") { e -> e.gateway.script(AdFormat.INTERSTITIAL, Fail()); e.show(placement) },
            Case("load never answers (timeout)") { e -> e.gateway.script(AdFormat.INTERSTITIAL, Never); e.show(placement) },
            Case("show error") { e ->
                e.controller.preload(placement); runCurrent()
                e.gateway.showFailureCode = "INTERNAL_ERROR"
                e.show(placement)
            },
            Case("show throws") { e ->
                e.controller.preload(placement); runCurrent()
                e.gateway.showThrows = IllegalStateException("fake")
                e.show(placement)
            },
            Case("owner cancelled while showing, ad never closes (watchdog)", shownAds = 1) { e ->
                e.controller.preload(placement); runCurrent()
                val n = AtomicInteger()
                val job = e.controller.showThen(e.activity, placement) { n.incrementAndGet() }
                runCurrent()
                job.cancel()
                advanceTimeBy(FullScreenGateOptions().watchdogMs); runCurrent()
                n
            },
            Case("daily cap reached", shownAds = 1, options = InterstitialOptions(minIntervalMs = 0, dailyCap = 1)) { e ->
                e.controller.preload(placement); runCurrent()
                e.show(placement); runCurrent()
                e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
                e.controller.preload(placement); runCurrent()
                e.show(placement)
            },
            Case("one in N skips this attempt", options = InterstitialOptions(minIntervalMs = 0, oneInN = 2)) { e -> e.show(placement) },
            Case("interval not elapsed", shownAds = 1, options = InterstitialOptions(minIntervalMs = 50_000)) { e ->
                e.controller.preload(placement); runCurrent()
                e.show(placement); runCurrent()
                e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
                e.show(placement)
            },
            Case("within the full-screen gap", gate = FullScreenGateOptions(minGapAfterFullScreenMs = 60_000)) { e ->
                val other = GateOwner("other")
                e.gate.reserve(other); e.gate.show(other); e.gate.closed(other)
                e.show(placement)
            },
            Case("gate busy") { e ->
                val other = GateOwner("other")
                e.gate.reserve(other)
                val n = e.show(placement); runCurrent()
                e.gate.release(other)
                n
            },
            Case("premium already on") { e -> e.state.setPremium(true); e.show(placement) },
            Case("kill switch") { e -> e.state.setKillSwitch(true); e.show(placement) },
            Case("premium turns on while waiting for the ad") { e ->
                e.gateway.script(AdFormat.INTERSTITIAL, Never)
                val n = e.show(placement); runCurrent()
                e.state.setPremium(true)
                n
            },
        )
        for (case in cases) {
            val e = env(case.options, case.gate)
            val n = case.body(this, e)
            advanceTimeBy(30_000); runCurrent()
            assertEquals(case.name, 1, n.get())
            assertEquals(case.name, GateState.Idle, e.gate.state.value)
            assertEquals(case.name, case.shownAds, e.gateway.shown.size)
        }
    }

    @Test
    fun `action waits for the dismiss and the ad is shown once with caps recorded`() = runTest {
        val e = env()
        e.controller.preload(placement); runCurrent()
        val n = e.show(placement); runCurrent()
        assertEquals(1, e.gateway.shown.size)
        assertEquals(0, n.get())
        assertTrue(e.gate.state.value is GateState.Showing)

        e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
        assertEquals(1, n.get())
        assertEquals(
            listOf(AdsEvent.Shown("inter"), AdsEvent.Dismissed("inter")),
            e.events.filter {
                it is AdsEvent.Shown ||
                    it is AdsEvent.Dismissed
            },
        )
    }

    @Test
    fun `host recreated - the action waits for a STARTED host and is never dropped or run on the dead one`() = runTest {
        val e = env()
        e.controller.preload(placement); runCurrent()
        val n = e.show(placement); runCurrent()
        // Rotation while the ad is up: old host destroyed, none started yet.
        every { e.activity.isDestroyed } returns true
        e.tracker.stopHost()
        e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, n.get())

        e.tracker.startHost(); runCurrent()
        assertEquals(1, n.get())
    }

    @Test
    fun `host destroyed while waiting for the ad - ad destroyed, gate released, action waits for the new host`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Load(delayMs = 1_000))
        val n = e.show(placement); runCurrent()
        advanceTimeBy(500)
        every { e.activity.isDestroyed } returns true
        e.tracker.stopHost()
        advanceTimeBy(600); runCurrent()

        assertEquals(GateState.Idle, e.gate.state.value)
        assertTrue(e.gateway.handles[0].destroyed)
        assertTrue(e.gateway.shown.isEmpty())
        assertEquals(0, n.get())
        e.tracker.startHost(); runCurrent()
        assertEquals(1, n.get())
    }

    @Test
    fun `owner cancelled while waiting - gate released and the action still runs once`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Never)
        val n = AtomicInteger()
        val job = e.controller.showThen(e.activity, placement) { n.incrementAndGet() }
        runCurrent()
        assertTrue(e.gate.state.value is GateState.Reserved)

        job.cancel(); runCurrent()
        assertEquals(GateState.Idle, e.gate.state.value)
        assertEquals(1, n.get())
    }

    @Test
    fun `recheck at show - kill switch flipped while the ad loads means no show and the ad is destroyed`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Load(delayMs = 1_000))
        val n = e.show(placement); runCurrent()
        advanceTimeBy(500)
        e.state.setKillSwitch(true)
        advanceTimeBy(600); runCurrent()

        assertTrue(e.gateway.shown.isEmpty())
        assertTrue(e.gateway.handles[0].destroyed)
        assertEquals(GateState.Idle, e.gate.state.value)
        assertEquals(1, n.get())
    }

    @Test
    fun `recheck at show - an ad older than the expiry is dropped, not shown`() = runTest {
        // The gateway stamps the handle as loaded a full TTL ago.
        val e = env(gatewayClock = FakeClock(-AdPool.DEFAULT_TTL_MS))
        e.gateway.script(AdFormat.INTERSTITIAL, Load(delayMs = 100))
        val n = e.show(placement)
        advanceTimeBy(200); runCurrent()

        assertTrue(e.gateway.shown.isEmpty())
        assertTrue(e.gateway.handles[0].destroyed)
        assertEquals(1, e.events.count { it == AdsEvent.Dropped("inter", DropReason.EXPIRED) })
        assertEquals(1, n.get())
    }

    @Test
    fun `failed preload retries after 5s then 15s then stops until the next preload call`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Fail())
        e.controller.preload(placement); runCurrent()
        assertEquals(1, e.interRequests())
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, e.interRequests())
        advanceTimeBy(1); runCurrent()
        assertEquals(2, e.interRequests())
        advanceTimeBy(14_999); runCurrent()
        assertEquals(2, e.interRequests())
        advanceTimeBy(1); runCurrent()
        assertEquals(3, e.interRequests())
        advanceTimeBy(600_000); runCurrent()
        assertEquals(3, e.interRequests())

        e.controller.preload(placement); runCurrent()
        assertEquals(4, e.interRequests())
    }

    @Test
    fun `retry stops as soon as a preload succeeds`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Fail(), Load())
        e.controller.preload(placement); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(2, e.interRequests())
    }

    @Test
    fun `owner cancelled during a load that later succeeds - the arrived ad is destroyed, not leaked`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.INTERSTITIAL, Load(delayMs = 1_000))
        val n = AtomicInteger()
        val job = e.controller.showThen(e.activity, placement) { n.incrementAndGet() }
        advanceTimeBy(500)
        job.cancel()
        advanceTimeBy(1_000); runCurrent()

        assertEquals(1, n.get())
        assertEquals(GateState.Idle, e.gate.state.value)
        assertTrue(e.gateway.shown.isEmpty())
        // Either destroyed or still held by the pool (then a later take can use it) - never orphaned.
        val handle = e.gateway.handles.single()
        val pooled = e.pool.take(placement, 1)
        assertTrue(handle.destroyed || pooled === handle)
    }

    @Test
    fun `owner cancelled while the ad is on screen - the action waits for the ad to end`() = runTest {
        val e = env()
        e.controller.preload(placement); runCurrent()
        val n = AtomicInteger()
        val job = e.controller.showThen(e.activity, placement) { n.incrementAndGet() }
        runCurrent()
        assertTrue(e.gate.state.value is GateState.Showing)

        job.cancel(); runCurrent()
        assertEquals(0, n.get())
        assertTrue(e.gate.state.value is GateState.Showing)

        e.gateway.dismiss(e.gateway.handles[0]); runCurrent()
        assertEquals(1, n.get())
        assertEquals(GateState.Idle, e.gate.state.value)
    }

    @Test
    fun `a gate-busy attempt does not use up a oneInN attempt`() = runTest {
        val e = env(InterstitialOptions(minIntervalMs = 0, oneInN = 2))
        e.controller.preload(placement); runCurrent()
        val other = GateOwner("other")
        e.gate.reserve(other)
        e.show(placement); runCurrent()
        e.gate.release(other)

        e.show(placement); runCurrent() // 1st counted attempt: skipped
        assertEquals(0, e.gateway.shown.size)
        e.show(placement); runCurrent() // 2nd: shown
        assertEquals(1, e.gateway.shown.size)
    }
}
