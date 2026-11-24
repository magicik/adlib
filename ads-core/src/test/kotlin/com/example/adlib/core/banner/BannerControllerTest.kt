package com.example.adlib.core.banner

import android.content.Context
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.BannerHandle
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
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
import io.mockk.mockk
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
class BannerControllerTest {
    private val config = PlacementConfig(
        totalTimeoutMs = 8_000,
        tiers = AdTiers(normal = listOf(AdUnitTier("b1", 3_000), AdUnitTier("b2", 3_000))),
    )
    private val placement = Placement("bottom", AdFormat.BANNER, default = config)
    private val context = mockk<Context>()

    private class Env(scope: TestScope, ready: Boolean, configProvider: ConfigProvider) {
        val serial = MainSerialDispatcher(StandardTestDispatcher(scope.testScheduler))
        val clock = FakeClock()
        val events = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(serial, AdsEventListener { events += it })
        val state = AdsState(false, configProvider, bus) { true }
        val store = FakeRevenueStore(RevenueSnapshot(mapOf("USD" to 1L), hasPaidEvent = true))
        val gateway = FakeAdGateway(scope.backgroundScope, serial, clock)
        val controller = BannerController(gateway, state, TestMode(store, bus), bus, { ready }, scope.backgroundScope)
        val results = mutableListOf<BannerHandle?>()
    }

    private fun TestScope.env(ready: Boolean = true, configProvider: ConfigProvider = ConfigProvider { null }) =
        Env(this, ready, configProvider).also { runCurrent() }

    private fun Env.request(p: Placement = placement) = controller.request(p, 360, context) { results += it }

    @Test
    fun `an ad is handed over once and paid and click events are forwarded`() = runTest {
        val e = env()
        e.request()
        runCurrent()
        val ad = e.results.single()!!
        assertFalse(e.gateway.handles.single().destroyed)
        val paid = AdPaid(7L, "EUR", "ESTIMATED")
        e.gateway.emitPaid(ad, paid)
        e.gateway.click(ad)
        runCurrent()
        assertTrue(e.events.contains(AdsEvent.Paid("bottom", paid)))
        assertTrue(e.events.contains(AdsEvent.Clicked("bottom")))
    }

    @Test
    fun `second tier is tried after the first fails`() = runTest {
        val e = env()
        e.gateway.script(AdFormat.BANNER, Fail(), Load())
        e.request()
        runCurrent()
        assertNotNull(e.results.single())
        assertEquals(listOf(AdFormat.BANNER to "b1", AdFormat.BANNER to "b2"), e.gateway.loadRequests)
    }

    @Test
    fun `every no-ad path answers null once and a late ad is destroyed`() = runTest {
        class Case(
            val name: String,
            val ready: Boolean = true,
            val configProvider: ConfigProvider = ConfigProvider { null },
            val arrange: Env.() -> Unit = {},
            val whileWaiting: Env.() -> Unit = {},
        )
        val cases = listOf(
            Case("not ready (no consent)", ready = false),
            Case("premium", arrange = { state.setPremium(true) }),
            Case("no fill", arrange = { gateway.script(AdFormat.BANNER, Fail()) }),
            Case("SDK never answers", arrange = { gateway.script(AdFormat.BANNER, Never) }),
            Case("answers after tier timeout", arrange = { gateway.script(AdFormat.BANNER, Load(3_500), Never) }),
            Case(
                "kill switch while waiting",
                arrange = { gateway.script(AdFormat.BANNER, Load(1_000)) },
                whileWaiting = { state.setKillSwitch(true) },
            ),
            Case(
                "config provider throws",
                configProvider = ConfigProvider { error("app bug") },
            ),
        )
        for (case in cases) {
            val e = env(case.ready, case.configProvider)
            case.arrange(e)
            e.request()
            runCurrent()
            case.whileWaiting(e)
            advanceTimeBy(config.totalTimeoutMs + 1)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(case.name, listOf<BannerHandle?>(null), e.results)
            assertTrue("${case.name}: arrived ads destroyed", e.gateway.handles.all { it.destroyed })
        }
    }
}
