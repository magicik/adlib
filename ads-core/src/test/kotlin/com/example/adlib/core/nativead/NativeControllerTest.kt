package com.example.adlib.core.nativead

import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.placement.Placement
import com.example.adlib.core.pool.AdPool
import com.example.adlib.core.state.AdsState
import com.example.adlib.core.state.ConfigProvider
import com.example.adlib.core.testmode.RevenueSnapshot
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeBehavior.Fail
import com.example.adlib.testing.FakeBehavior.Load
import com.example.adlib.testing.FakeBehavior.Never
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeRevenueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeControllerTest {
    private val config = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000))))
    private val placement = Placement("feed", AdFormat.NATIVE, default = config)
    private val revenueSeen = RevenueSnapshot(mapOf("USD" to 1_000L), hasPaidEvent = true)

    private class Env(scope: TestScope, revenue: RevenueSnapshot, configProvider: ConfigProvider) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val clock = FakeClock()
        val events = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(serial, AdsEventListener { events += it })
        val state = AdsState(false, configProvider, bus) { true }
        val store = FakeRevenueStore(revenue)
        val testMode = TestMode(store, bus)
        val gateway = FakeAdGateway(scope.backgroundScope, serial, clock)
        val pool = AdPool(gateway, state, testMode, { true }, bus, clock, scope.backgroundScope)
        val controller = NativeController(pool, gateway, state, testMode, bus, scope.backgroundScope)
        val results = mutableListOf<NativeHandle?>()

        fun request(placement: Placement) = controller.request(placement) { results += it }
    }

    private fun TestScope.env(revenue: RevenueSnapshot = revenueSeen, configProvider: ConfigProvider = ConfigProvider { null }) =
        Env(this, revenue, configProvider).also { runCurrent() }

    @Test
    fun `an allowed ad is handed over once and reports impression, click and paid`() = runTest {
        val e = env()
        e.request(placement)
        runCurrent()

        val ad = e.results.single()
        assertNotNull(ad)
        assertFalse(e.gateway.handles.single().destroyed)
        val paid = AdPaid(500L, "EUR", "ESTIMATED")
        e.gateway.emitPaid(ad!!, paid)
        e.gateway.click(ad)
        runCurrent()
        assertTrue(e.events.contains(AdsEvent.Paid("feed", paid)))
        assertTrue(e.events.contains(AdsEvent.Clicked("feed")))
        assertEquals("paid feeds test mode (R27b)", 500L, e.store.current.totalMicrosByCurrency["EUR"])
    }

    @Test
    fun `every no-show path answers null exactly once and destroys any ad that arrived`() = runTest {
        class Case(
            val name: String,
            val placement: Placement = this@NativeControllerTest.placement,
            val revenue: RevenueSnapshot = revenueSeen,
            val configProvider: ConfigProvider = ConfigProvider { null },
            val arrange: Env.() -> Unit = {},
            val whileWaiting: Env.() -> Unit = {},
            val expectEvent: ((AdsEvent) -> Boolean)? = null,
        )

        val cases = listOf(
            Case("disabled", placement = placement.copy(default = config.copy(enable = false))),
            Case("premium", arrange = { state.setPremium(true) }),
            Case(
                "gone in test mode",
                placement = placement.copy(default = config.copy(goneInTestMode = true)),
                revenue = revenueSeen.copy(nativeTestAdSeen = true),
            ),
            Case("R27b fresh device", revenue = RevenueSnapshot(), expectEvent = { it is AdsEvent.HiddenOnPurpose }),
            Case("no fill", arrange = { gateway.script(AdFormat.NATIVE, Fail()) }),
            Case("SDK never answers", arrange = { gateway.script(AdFormat.NATIVE, Never) }),
            Case(
                "kill switch while waiting",
                arrange = { gateway.script(AdFormat.NATIVE, Load(1_000)) },
                whileWaiting = { state.setKillSwitch(true) },
            ),
            Case(
                "config provider throws",
                configProvider = ConfigProvider { error("app bug") },
                expectEvent = { it is AdsEvent.LoadFailed && it.code == NativeController.REQUEST_THREW },
            ),
            Case(
                "setEventListener throws",
                arrange = { gateway.setEventListenerThrows = IllegalStateException("sdk") },
                expectEvent = { it is AdsEvent.LoadFailed && it.code == NativeController.REQUEST_THREW },
            ),
        )
        for (case in cases) {
            val e = env(case.revenue, case.configProvider)
            case.arrange(e)
            e.request(case.placement)
            runCurrent()
            case.whileWaiting(e)
            advanceTimeBy(PlacementConfig.DEFAULT_TOTAL_TIMEOUT_MS + 1)
            runCurrent()

            assertEquals(case.name, listOf<NativeHandle?>(null), e.results)
            assertTrue("${case.name}: arrived ads destroyed", e.gateway.handles.all { it.destroyed })
            case.expectEvent?.let { assertTrue("${case.name}: event", e.events.any(it)) }
        }
    }
}
