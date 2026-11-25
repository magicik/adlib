package com.example.adlib.core.appopen

import android.app.Activity
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.events.DropReason
import com.example.adlib.core.fullscreen.FullScreenGate
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
import com.example.adlib.testing.FakeAppOpenOverlay
import com.example.adlib.testing.FakeBehavior
import com.example.adlib.testing.FakeBehavior.Fail
import com.example.adlib.testing.FakeBehavior.Load
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeForegroundSource
import com.example.adlib.testing.FakeRevenueStore
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppOpenResumeControllerTest {
    private val placement = Placement(
        "app_open", AdFormat.APP_OPEN,
        default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("ao1", 10_000)))),
    )

    /** A lock / PIN screen the app excludes. */
    private open class LockActivity : Activity()

    private inner class Env(val scope: TestScope, options: AppOpenResumeOptions = AppOpenResumeOptions()) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val clock = FakeClock()
        val events = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(serial, AdsEventListener { events += it })
        var canRequestAds = true
        val state = AdsState(false, { null }, bus) { canRequestAds }
        val testMode = TestMode(FakeRevenueStore(), bus)
        val gateway = FakeAdGateway(scope.backgroundScope, serial, clock)
        val tracker = FakeActivityTracker()
        val foreground = FakeForegroundSource()
        val formShowing = MutableStateFlow(false)
        val overlay = FakeAppOpenOverlay()
        val pool = AdPool(gateway, state, testMode, { true }, bus, clock, scope.backgroundScope)
        val gate = FullScreenGate(state.isPremium, tracker, bus, clock, scope.backgroundScope)
        val controller = AppOpenResumeController(
            placement, pool, gate, gateway, state, testMode, tracker, foreground, formShowing, overlay,
            bus, clock, scope.backgroundScope, options,
        )
        val activity = mockk<Activity>(relaxed = true)

        /** Cold start: the first foreground is not a return. */
        fun launch() {
            controller.attachResume()
            scope.runCurrent()
            foreground.toForeground()
            tracker.resumeAppActivity(activity)
            scope.runCurrent()
        }

        fun leave() {
            tracker.pauseAppActivity()
            foreground.toBackground()
            scope.runCurrent()
        }

        /** ProcessLifecycle ON_START first, the host's onResume after it. */
        fun comeBack(host: Activity = activity) {
            foreground.toForeground()
            scope.runCurrent()
            tracker.resumeAppActivity(host)
            scope.runCurrent()
        }

        fun roundTrip(awayMs: Long = 1_000) {
            leave()
            elapse(awayMs)
            comeBack()
        }

        fun elapse(ms: Long) {
            clock.advanceBy(ms)
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }

        fun cacheOne() {
            pool.preload(placement)
            scope.runCurrent()
        }

        fun dismissShown() {
            gateway.dismiss(gateway.shown.last())
            scope.runCurrent()
        }

        fun requests() = gateway.loadRequests.count { it.first == AdFormat.APP_OPEN }

        /** Premium flushes the pool: afterwards every handle ever loaded must be destroyed or still on screen. */
        fun assertNoLeak(onScreen: Set<Any> = emptySet()) {
            state.setPremium(true)
            scope.runCurrent()
            val leaked = gateway.handles.filter { !it.destroyed && it !in onScreen }
            assertEquals("leaked handles", emptyList<Any>(), leaked)
        }
    }

    @Test
    fun `launch is not a return and attaching twice watches returns once`() = runTest {
        val env = Env(this)
        env.cacheOne()
        env.controller.attachResume()
        env.launch() // attaches a second time
        assertEquals(0, env.gateway.shown.size)
        assertEquals(0, env.overlay.hosts.size)

        // A second watcher would see the return again after the first consumed the click skip.
        env.bus.emit(AdsEvent.Clicked("home_native"))
        env.roundTrip()
        assertEquals(0, env.gateway.shown.size)

        env.roundTrip()
        assertEquals(1, env.gateway.shown.size)
        assertTrue(env.gate.state.value is GateState.Showing)
    }

    @Test
    fun `first return loads with the overlay and shows only within reactive timeout and overlay cap`() = runTest {
        data class Case(val reactiveMs: Long, val overlayMaxMs: Long, val adAfterMs: Long, val shown: Boolean)
        for (case in listOf(
            Case(5_000, 5_000, 2_000, shown = true),
            Case(5_000, 5_000, 6_000, shown = false),
            Case(5_000, 3_000, 4_000, shown = false),
            Case(3_000, 5_000, 4_000, shown = false),
        )) {
            val env = Env(this, AppOpenResumeOptions(reactiveShowTimeoutMs = case.reactiveMs, overlayMaxMs = case.overlayMaxMs))
            env.gateway.script(AdFormat.APP_OPEN, Load(case.adAfterMs))
            env.launch()
            env.roundTrip()
            assertEquals("$case overlay up", 1, env.overlay.visible)
            assertSame(env.activity, env.overlay.hosts.single())
            assertTrue("$case reserved while loading", env.gate.state.value is GateState.Reserved)

            env.elapse(case.adAfterMs)
            assertEquals("$case overlay gone", 0, env.overlay.visible)
            assertEquals("$case", if (case.shown) 1 else 0, env.gateway.shown.size)
            if (case.shown) {
                assertSame(env.activity, env.gateway.shownOn.single())
                env.dismissShown()
            } else {
                // Late ad: cached, shown at once on the next return without an overlay.
                assertEquals(GateState.Idle, env.gate.state.value)
                env.roundTrip()
                assertEquals("$case cached for next", env.gateway.handles.first(), env.gateway.shown.single())
                assertEquals(1, env.overlay.hosts.size)
                env.dismissShown()
            }
            assertEquals(GateState.Idle, env.gate.state.value)
        }
    }

    @Test
    fun `overlay cap is bounded and never infinite`() {
        for (bad in listOf(0L, AppOpenResumeOptions.MAX_OVERLAY_MS + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { AppOpenResumeOptions(overlayMaxMs = bad) }
        }
        assertEquals(3_000, AppOpenResumeOptions(reactiveShowTimeoutMs = 3_000, overlayMaxMs = 9_000).windowMs)
    }

    @Test
    fun `armed after a no-fill first try - later returns never block and only use or preload the cache`() = runTest {
        val env = Env(this)
        env.gateway.script(AdFormat.APP_OPEN, Fail(500), Load(1_000))
        env.launch()

        env.roundTrip() // first return: reactive, no fill
        env.elapse(500)
        assertEquals(0, env.gateway.shown.size)
        assertEquals(0, env.overlay.visible)
        assertEquals(1, env.requests())

        env.roundTrip() // armed, nothing cached: silent preload, no overlay, nothing shown
        assertEquals(1, env.overlay.hosts.size)
        assertEquals(GateState.Idle, env.gate.state.value)
        assertEquals(2, env.requests())
        env.elapse(1_000)
        assertEquals(0, env.gateway.shown.size)

        env.roundTrip() // cached: shown at once, one next ad preloaded
        assertEquals(1, env.gateway.shown.size)
        assertEquals(1, env.overlay.hosts.size)
        assertEquals(3, env.requests())
        env.dismissShown()
        env.assertNoLeak()
    }

    @Test
    fun `show conditions - a return that fails one shows nothing and loads nothing`() = runTest {
        data class Case(val name: String, val options: AppOpenResumeOptions = AppOpenResumeOptions(), val setup: Env.() -> Unit)
        for (case in listOf(
            Case("away shorter than minBackgroundMs", AppOpenResumeOptions(minBackgroundMs = 5_000)) {},
            Case("premium already on") { state.setPremium(true) },
            Case("kill switch") { state.setKillSwitch(true) },
            Case("no consent") { canRequestAds = false },
            Case("gate busy") { gate.reserve(GateOwner("rewarded")) },
            Case("within the full-screen gap") {
                val other = GateOwner("inter")
                gate.reserve(other)
                gate.show(other)
                gate.closed(other)
            },
        )) {
            val env = Env(this, case.options)
            env.launch()
            env.cacheOne()
            val before = env.requests()
            case.setup(env)
            env.roundTrip(awayMs = 1_000)
            env.elapse(10_000)
            assertEquals(case.name, 0, env.gateway.shown.size)
            assertEquals(case.name, 0, env.overlay.hosts.size)
            assertEquals(case.name, before, env.requests())
        }
        // The same return with enough time away does show.
        val env = Env(this, AppOpenResumeOptions(minBackgroundMs = 5_000))
        env.launch()
        env.cacheOne()
        env.roundTrip(awayMs = 5_000)
        assertEquals(1, env.gateway.shown.size)
    }

    @Test
    fun `ad older than 4h is dropped, never shown`() = runTest {
        val env = Env(this)
        env.gateway.script(AdFormat.APP_OPEN, Load(0), FakeBehavior.Never)
        env.launch()
        env.cacheOne()
        val old = env.gateway.handles.single()
        env.clock.advanceBy(AdPool.APP_OPEN_TTL_MS)

        env.roundTrip()
        env.elapse(10_000)
        assertTrue(old.destroyed)
        assertTrue(env.gateway.shown.isEmpty())
        assertTrue(env.events.contains(AdsEvent.Dropped("app_open", DropReason.EXPIRED)))
    }

    @Test
    fun `skip the next return - click, consent form, external flow, excluded activity`() = runTest {
        data class Case(
            val name: String,
            val options: AppOpenResumeOptions = AppOpenResumeOptions(),
            val awayMs: Long = 1_000,
            val host: (Env) -> Activity = { it.activity },
            val skipsAlsoTheReturnAfter: Boolean = false,
            val setup: Env.() -> Unit,
        )
        val lock = mockk<LockActivity>(relaxed = true)
        for (case in listOf(
            Case("native click") { bus.emit(AdsEvent.Clicked("home_native")) },
            Case("interstitial click") { bus.emit(AdsEvent.Clicked("inter")) },
            Case("pause, back within 60s", awayMs = 10_000) { controller.pauseForExternalFlow() },
            Case("form on screen", skipsAlsoTheReturnAfter = true) { formShowing.value = true },
            Case(
                "excluded activity on top",
                AppOpenResumeOptions(excludedActivities = setOf(LockActivity::class.java)),
                host = { lock },
                skipsAlsoTheReturnAfter = true,
            ) {},
        )) {
            val env = Env(this, case.options)
            env.launch()
            env.cacheOne()
            case.setup(env)
            env.scope.runCurrent()
            env.leave()
            env.elapse(case.awayMs)
            env.comeBack(case.host(env))
            assertEquals(case.name, 0, env.gateway.shown.size)

            // One return only for the one-shots; live conditions still hold.
            env.leave()
            env.comeBack(case.host(env))
            assertEquals(case.name, if (case.skipsAlsoTheReturnAfter) 0 else 1, env.gateway.shown.size)
        }
    }

    @Test
    fun `click skip expires after the pause window`() = runTest {
        for ((awayMs, shown) in listOf(60_000L to 0, 60_001L to 1)) {
            val env = Env(this)
            env.launch()
            env.cacheOne()
            env.bus.emit(AdsEvent.Clicked("home_native"))
            env.scope.runCurrent()
            env.roundTrip(awayMs = awayMs)
            assertEquals("away $awayMs", shown, env.gateway.shown.size)
        }
    }

    @Test
    fun `pause never resumed expires by itself`() = runTest {
        val env = Env(this)
        env.launch()
        env.cacheOne()
        env.controller.pauseForExternalFlow(maxMs = 60_000)
        env.roundTrip(awayMs = 60_001)
        assertEquals(1, env.gateway.shown.size)
    }

    @Test
    fun `background mid-load hides the overlay, frees the gate and keeps the late ad for next return`() = runTest {
        val env = Env(this)
        env.gateway.script(AdFormat.APP_OPEN, Load(3_000))
        env.launch()
        env.roundTrip()
        env.elapse(1_000)
        env.leave()
        assertEquals(0, env.overlay.visible)
        assertEquals(GateState.Idle, env.gate.state.value)

        env.elapse(2_000) // the ad lands in the pool while away
        assertEquals(0, env.gateway.shown.size)
        env.comeBack()
        assertEquals(env.gateway.handles.first(), env.gateway.shown.single())
        assertEquals("no second overlay", 1, env.overlay.hosts.size)
    }

    @Test
    fun `an overlay whose show throws does not crash, frees the gate, reports and keeps the ad coming`() = runTest {
        val env = Env(this)
        env.overlay.showThrows = IllegalStateException("overlay boom")
        env.launch()
        env.roundTrip() // runTest fails on an uncaught exception
        assertEquals(GateState.Idle, env.gate.state.value)
        assertEquals(0, env.gateway.shown.size)
        assertEquals(1, env.events.count { it is AdsEvent.LoadFailed && it.code == "OVERLAY_THREW" })
        assertEquals("ad preloaded for next time", 1, env.requests())
        env.assertNoLeak()
    }

    @Test
    fun `rotation keeps state - the ad shows on the new top Activity and rotation is not a return`() = runTest {
        val env = Env(this)
        env.gateway.script(AdFormat.APP_OPEN, Load(2_000))
        env.launch()
        env.roundTrip()
        // Rotation mid-load: old host paused and destroyed, a new one resumes; foreground never changes.
        val rotated = mockk<Activity>(relaxed = true)
        env.tracker.pauseAppActivity()
        env.tracker.resumeAppActivity(rotated)
        env.scope.runCurrent()
        env.elapse(2_000)
        assertSame(rotated, env.gateway.shownOn.single())
        env.dismissShown()

        val again = mockk<Activity>(relaxed = true)
        env.tracker.pauseAppActivity()
        env.tracker.resumeAppActivity(again)
        env.elapse(60_000) // also past the full-screen gap of the first ad
        assertEquals("rotation is no return", 1, env.gateway.shown.size)

        env.leave()
        env.comeBack(again)
        assertEquals(2, env.gateway.shown.size)
        assertEquals("overlay only on the first return", 1, env.overlay.hosts.size)
    }

    @Test
    fun `gate reservation and overlay are released on every branch with no leaked ad`() = runTest {
        data class Case(
            val name: String,
            val ad: FakeBehavior,
            val options: AppOpenResumeOptions = AppOpenResumeOptions(),
            val before: Env.() -> Unit = {},
            val during: Env.() -> Unit = {},
        )
        val lock = mockk<LockActivity>(relaxed = true)
        for (case in listOf(
            Case("no fill", Fail(1_000)),
            Case("timeout", Load(10_000)),
            Case("owner cancelled: background", Load(2_000), during = { leave() }),
            Case("premium during the wait", Load(2_000), during = { state.setPremium(true) }),
            Case("host destroyed before the ad", Load(2_000), during = { tracker.pauseAppActivity() }),
            Case(
                "excluded activity on top when the ad arrives", Load(2_000),
                AppOpenResumeOptions(excludedActivities = setOf(LockActivity::class.java)),
                during = {
                    tracker.pauseAppActivity()
                    tracker.resumeAppActivity(lock)
                },
            ),
            Case("show fails", Load(1_000), before = { gateway.showFailureCode = "INTERNAL" }),
            Case("show throws", Load(1_000), before = { gateway.showThrows = IllegalStateException("boom") }),
        )) {
            val env = Env(this, case.options)
            env.gateway.script(AdFormat.APP_OPEN, case.ad)
            case.before(env)
            env.launch()
            env.roundTrip()
            env.elapse(500)
            case.during(env)
            env.scope.runCurrent()
            env.elapse(11_000)
            assertEquals(case.name, GateState.Idle, env.gate.state.value)
            assertEquals(case.name, 0, env.overlay.visible)
            env.assertNoLeak()
        }
    }

    @Test
    fun `show waits for the host onResume so the watchdog does not free the gate while the ad comes up`() = runTest {
        val env = Env(this)
        env.launch()
        env.cacheOne()
        env.leave()
        env.foreground.toForeground() // ProcessLifecycle ON_START: host started, not resumed yet
        env.scope.runCurrent()
        assertEquals(0, env.gateway.shown.size)

        env.tracker.resumeAppActivity(env.activity) // the host's onResume
        env.scope.runCurrent()
        assertEquals(1, env.gateway.shown.size)
        env.tracker.pauseAppActivity() // the ad Activity covers the host

        env.elapse(60_000)
        assertTrue("still showing", env.gate.state.value is GateState.Showing)
        assertTrue(env.events.none { it is AdsEvent.WatchdogFired })
        env.dismissShown()
        assertEquals(GateState.Idle, env.gate.state.value)
        assertTrue(env.gate.isWithinGap())
    }
}
