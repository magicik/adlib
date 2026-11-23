package com.example.adlib.core.testmode

import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.AdPaid
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import com.example.adlib.testing.FakeRevenueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestModeTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = MainSerialDispatcher(StandardTestDispatcher(scheduler))
    private val received = mutableListOf<AdsEvent>()
    private val bus = AdsEventBus(dispatcher, AdsEventListener { received += it })
    private val store = FakeRevenueStore()
    private val placement = Placement("n", AdFormat.NATIVE, default = PlacementConfig())

    private fun mode(initial: RevenueSnapshot = RevenueSnapshot()): TestMode {
        store.current = initial
        return TestMode(store, bus)
    }

    private fun paid(micros: Long, currency: String = "USD") = AdPaid(micros, currency, "PRECISE")

    @Test
    fun `effective interstitial interval is max with 60s only in test mode`() {
        // (testMode, configMs) -> expected
        val cases = listOf(
            Triple(true, 0L, 60_000L),
            Triple(true, 30_000L, 60_000L),
            Triple(true, 90_000L, 90_000L),
            Triple(false, 0L, 0L),
            Triple(false, 30_000L, 30_000L),
        )
        for ((test, configMs, expected) in cases) {
            val m = mode(RevenueSnapshot(nativeTestAdSeen = test))
            assertEquals("test=$test cfg=$configMs", expected, m.effectiveInterstitialIntervalMs(configMs))
        }
    }

    @Test
    fun `native refresh is disabled in test mode only`() {
        assertTrue(mode().nativeRefreshEnabled)
        assertFalse(mode(RevenueSnapshot(nativeTestAdSeen = true)).nativeRefreshEnabled)
    }

    @Test
    fun `headline containing Test Ad marks test mode and is persisted`() {
        val cases = mapOf("Test Ad: Great App" to true, "test ad" to true, "Real headline" to false, "" to false, null to false)
        for ((headline, expected) in cases) {
            store.saved.clear()
            val m = mode()
            m.onNativeHeadline(headline)
            assertEquals("headline=$headline", expected, m.isTestMode)
            assertEquals("headline=$headline", expected, store.saved.isNotEmpty())
        }
    }

    @Test
    fun `revenue is summed per currency and never across currencies`() {
        val m = mode()
        m.onPaid(paid(1_000, "USD"))
        m.onPaid(paid(500, "USD"))
        m.onPaid(paid(7_000, "EUR"))
        m.onPaid(paid(0, "JPY"))
        assertEquals(mapOf("USD" to 1_500L, "EUR" to 7_000L), store.current.totalMicrosByCurrency)
    }

    @Test
    fun `paid events decide test mode from the totals`() {
        // list of paid values -> expected test mode
        val cases = listOf(
            emptyList<Long>() to false,
            listOf(0L) to true,
            listOf(0L, 0L) to true,
            listOf(0L, 10L) to false,
            listOf(10L) to false,
        )
        for ((values, expected) in cases) {
            val m = mode()
            values.forEach { m.onPaid(paid(it)) }
            assertEquals("values=$values", expected, m.isTestMode)
        }
    }

    @Test
    fun `state survives a restart through the store`() {
        val first = mode()
        first.onPaid(paid(0))
        assertTrue(TestMode(store, bus).isTestMode)
    }

    @Test
    fun `native hidden until first paid impression and says so`() {
        val m = mode()
        assertTrue(m.shouldHideNative(placement))
        scheduler.advanceUntilIdle()
        assertEquals(listOf<AdsEvent>(AdsEvent.HiddenOnPurpose("n", TestMode.HIDDEN_R27B_MESSAGE)), received)
        assertTrue(TestMode.HIDDEN_R27B_MESSAGE.contains("hidden on purpose (R27b)"))

        m.onPaid(paid(100))
        received.clear()
        assertFalse(m.shouldHideNative(placement))
        scheduler.advanceUntilIdle()
        assertTrue(received.isEmpty())
    }

    @Test
    fun `gone_in_test_mode applies to native interstitial app open but not rewarded`() {
        val gone = PlacementConfig(goneInTestMode = true)
        val notGone = PlacementConfig(goneInTestMode = false)
        // (format, testMode, config) -> expected
        val cases = listOf(
            Triple(AdFormat.NATIVE, true, gone) to true,
            Triple(AdFormat.INTERSTITIAL, true, gone) to true,
            Triple(AdFormat.APP_OPEN, true, gone) to true,
            Triple(AdFormat.REWARDED, true, gone) to false,
            Triple(AdFormat.NATIVE, false, gone) to false,
            Triple(AdFormat.NATIVE, true, notGone) to false,
        )
        for ((input, expected) in cases) {
            val (format, test, config) = input
            val m = mode(RevenueSnapshot(nativeTestAdSeen = test))
            assertEquals("$format test=$test gone=${config.goneInTestMode}", expected, m.isGone(format, config))
        }
    }
}
