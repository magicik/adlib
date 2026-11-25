package com.example.adlib.core.fullscreen

import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.time.Clock
import com.example.adlib.testing.FakeActivityTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class FullScreenGateTest {
    private class Env(scope: TestScope, options: FullScreenGateOptions) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val events = mutableListOf<AdsEvent>()
        val premium = MutableStateFlow(false)
        val tracker = FakeActivityTracker()
        val forced = mutableListOf<Pair<String, ForcedRelease>>()
        val gate = FullScreenGate(
            premium,
            tracker,
            AdsEventBus(serial, AdsEventListener { events += it }),
            Clock { scope.testScheduler.currentTime },
            scope.backgroundScope,
            options,
        )
        val a = owner("a")
        val b = owner("b")

        fun owner(name: String) = GateOwner(name) { forced += name to it }

        fun watchdogEvents() = events.filterIsInstance<AdsEvent.WatchdogFired>()
    }

    private fun TestScope.env(options: FullScreenGateOptions = FullScreenGateOptions()) = Env(this, options).also { runCurrent() }

    @Test
    fun `transitions - only the holder moves the gate and a second reserve is refused while not Idle`() = runTest {
        data class Case(val name: String, val steps: Env.() -> List<Boolean>, val expected: List<Boolean>, val end: Env.() -> GateState)

        val cases = listOf(
            Case("reserve from Idle", { listOf(gate.reserve(a)) }, listOf(true), { GateState.Reserved(a) }),
            Case(
                "second reserve while Reserved",
                { listOf(gate.reserve(a), gate.reserve(b)) },
                listOf(true, false),
                { GateState.Reserved(a) },
            ),
            Case("same owner reserves twice", { listOf(gate.reserve(a), gate.reserve(a)) }, listOf(true, false), { GateState.Reserved(a) }),
            Case("reserve then show", { listOf(gate.reserve(a), gate.show(a)) }, listOf(true, true), { GateState.Showing(a) }),
            Case("second reserve while Showing", {
                listOf(gate.reserve(a), gate.show(a), gate.reserve(b))
            }, listOf(true, true, false), { GateState.Showing(a) }),
            Case("show without reserve", { listOf(gate.show(a)) }, listOf(false), { GateState.Idle }),
            Case("show by a non-holder", { listOf(gate.reserve(a), gate.show(b)) }, listOf(true, false), { GateState.Reserved(a) }),
            Case(
                "show twice",
                { listOf(gate.reserve(a), gate.show(a), gate.show(a)) },
                listOf(true, true, false),
                { GateState.Showing(a) },
            ),
            Case("release while Reserved", { listOf(gate.reserve(a)).also { gate.release(a) } }, listOf(true), { GateState.Idle }),
            Case(
                "closed while Showing",
                { listOf(gate.reserve(a), gate.show(a)).also { gate.closed(a) } },
                listOf(true, true),
                { GateState.Idle },
            ),
            Case(
                "release by a non-holder is ignored",
                { listOf(gate.reserve(a)).also { gate.release(b) } },
                listOf(true),
                { GateState.Reserved(a) },
            ),
            Case("closed by a non-holder is ignored", {
                listOf(gate.reserve(a), gate.show(a)).also { gate.closed(b) }
            }, listOf(true, true), { GateState.Showing(a) }),
            Case("reserve again after release", {
                listOf(gate.reserve(a)).also { gate.release(a) } + gate.reserve(b)
            }, listOf(true, true), { GateState.Reserved(b) }),
        )
        for (case in cases) {
            val env = env()
            assertEquals(case.name, case.expected, case.steps(env))
            assertEquals(case.name, case.end(env), env.gate.state.value)
        }
    }

    @Test
    fun `watchdog - a non-ad app Activity resume after show frees the gate, a resume before show does not count`() = runTest {
        val env = env()
        env.tracker.resumeAppActivity() // before the show: must not count
        env.gate.reserve(env.a)
        env.gate.show(env.a)
        runCurrent()
        assertEquals(GateState.Showing(env.a), env.gate.state.value)

        env.tracker.resumeAppActivity()
        runCurrent()

        assertEquals(GateState.Idle, env.gate.state.value)
        assertEquals(listOf("a" to ForcedRelease.WATCHDOG_RESUME), env.forced)
        assertEquals(listOf(AdsEvent.WatchdogFired("a", "watchdog_resume")), env.watchdogEvents())
    }

    @Test
    fun `watchdog - no callback and no resume frees the gate after the configured cap`() = runTest {
        data class Case(val options: FullScreenGateOptions, val capMs: Long)
        for (case in listOf(Case(FullScreenGateOptions(), 120_000L), Case(FullScreenGateOptions(watchdogMs = 5_000L), 5_000L))) {
            val env = env(case.options)
            env.gate.reserve(env.a)
            env.gate.show(env.a)

            advanceTimeBy(case.capMs - 1)
            runCurrent()
            assertEquals("cap ${case.capMs}", GateState.Showing(env.a), env.gate.state.value)

            advanceTimeBy(1)
            runCurrent()
            assertEquals("cap ${case.capMs}", GateState.Idle, env.gate.state.value)
            assertEquals(listOf("a" to ForcedRelease.WATCHDOG_TIMEOUT), env.forced)
            assertEquals(listOf(AdsEvent.WatchdogFired("a", "watchdog_timeout")), env.watchdogEvents())
        }
    }

    @Test
    fun `watchdog - a normal dismiss stops it and a late dismiss of the old owner never frees the next owner`() = runTest {
        val env = env()
        env.gate.reserve(env.a)
        env.gate.show(env.a)
        env.gate.closed(env.a)
        advanceTimeBy(FullScreenGateOptions().watchdogMs * 2)
        env.tracker.resumeAppActivity()
        runCurrent()
        assertTrue(env.watchdogEvents().isEmpty())
        assertTrue(env.forced.isEmpty())

        // b's show loses its dismiss; watchdog frees it; c reserves; b's dismiss arrives late.
        val c = env.owner("c")
        env.gate.reserve(env.b)
        env.gate.show(env.b)
        env.tracker.resumeAppActivity()
        runCurrent()
        assertTrue(env.gate.reserve(c))
        env.gate.closed(env.b)
        assertEquals(GateState.Reserved(c), env.gate.state.value)
    }

    @Test
    fun `gap - starts when ANY full-screen closes or the watchdog fires, not when the gate is given back without an ad`() = runTest {
        data class Case(val name: String, val end: Env.() -> Unit, val gap: Boolean)

        val cases = listOf(
            Case("dismissed", { gate.closed(a) }, true),
            Case("watchdog by resume", { tracker.resumeAppActivity() }, true),
            Case("show failed", { gate.release(a) }, false),
        )
        for (case in cases) {
            val env = env(FullScreenGateOptions(minGapAfterFullScreenMs = 60_000L, watchdogMs = 600_000L))
            assertFalse(case.name, env.gate.isWithinGap())
            env.gate.reserve(env.a)
            env.gate.show(env.a)
            case.end(env)
            runCurrent()
            assertEquals(case.name, GateState.Idle, env.gate.state.value)
            assertEquals(case.name, case.gap, env.gate.isWithinGap())
            advanceTimeBy(59_999)
            assertEquals(case.name, case.gap, env.gate.isWithinGap())
            advanceTimeBy(1)
            assertFalse(case.name, env.gate.isWithinGap())
        }

        val reservedOnly = env()
        reservedOnly.gate.reserve(reservedOnly.a)
        reservedOnly.gate.closed(reservedOnly.a) // never shown: nothing closed on screen
        assertFalse(reservedOnly.gate.isWithinGap())
    }

    @Test
    fun `premium on while Reserved frees the gate and tells the owner, a Showing ad is left to close`() = runTest {
        val env = env()
        env.gate.reserve(env.a)
        env.premium.value = true
        runCurrent()
        assertEquals(GateState.Idle, env.gate.state.value)
        assertEquals(listOf("a" to ForcedRelease.PREMIUM), env.forced)

        val showing = env()
        showing.gate.reserve(showing.a)
        showing.gate.show(showing.a)
        showing.premium.value = true
        runCurrent()
        assertEquals(GateState.Showing(showing.a), showing.gate.state.value)
        assertTrue(showing.forced.isEmpty())
    }

    @Test
    fun `isAdActivity - only SDK ad Activities are excluded from app resumes`() {
        val cases = mapOf(
            "com.google.android.libraries.ads.mobile.sdk.common.AdActivity" to true,
            "com.google.android.gms.ads.AdActivity" to true,
            "com.example.adlib.sample.MainActivity" to false,
            "com.google.android.libraries.places.PlacesActivity" to false,
        )
        for ((className, expected) in cases) assertEquals(className, expected, isAdActivity(className))
    }
}
