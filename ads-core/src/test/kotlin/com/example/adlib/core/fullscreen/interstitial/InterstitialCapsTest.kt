package com.example.adlib.core.fullscreen.interstitial

import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.testmode.RevenueSnapshot
import com.example.adlib.core.testmode.TestMode
import com.example.adlib.testing.FakeCapStore
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeRevenueStore
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class InterstitialCapsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val clock = FakeClock()
    private val store = FakeCapStore()

    private fun caps(options: InterstitialOptions, testModeOn: Boolean = false, store: CapStore = this.store): InterstitialCaps {
        val bus = AdsEventBus(MainSerialDispatcher(StandardTestDispatcher()))
        val testMode = TestMode(FakeRevenueStore(RevenueSnapshot(nativeTestAdSeen = testModeOn)), bus)
        return InterstitialCaps(options, clock, testMode, store, utc)
    }

    @Test
    fun `daily cap blocks after N shows and resets at the Clock day boundary`() {
        val caps = caps(InterstitialOptions(minIntervalMs = 0, dailyCap = 2))
        clock.set(DAY_MS - 1_000)
        val before = buildList {
            repeat(3) { add(caps.tryConsume()); if (last() == CapDecision.ALLOWED) caps.recordShown() }
        }
        assertEquals(listOf(CapDecision.ALLOWED, CapDecision.ALLOWED, CapDecision.DAILY_CAP), before)

        clock.advanceBy(1_000) // 00:00:00 of the next day
        assertEquals(CapDecision.ALLOWED, caps.tryConsume())
    }

    @Test
    fun `interval - config value normally, at least 60s in test mode`() {
        data class Case(val name: String, val testMode: Boolean, val configMs: Long, val blockedAtMs: Long, val allowedAtMs: Long)
        val cases = listOf(
            Case("normal 50s", false, 50_000, 49_999, 50_000),
            Case("test mode raises 10s to 60s", true, 10_000, 59_999, 60_000),
            Case("test mode keeps a larger config", true, 90_000, 89_999, 90_000),
            Case("interval 0 = off", false, 0, -1, 0),
        )
        for (c in cases) {
            clock.set(1_000_000)
            val caps = caps(InterstitialOptions(minIntervalMs = c.configMs), c.testMode, FakeCapStore())
            caps.recordShown()
            if (c.blockedAtMs >= 0) {
                clock.set(1_000_000 + c.blockedAtMs)
                assertEquals(c.name, CapDecision.INTERVAL, caps.tryConsume())
            }
            clock.set(1_000_000 + c.allowedAtMs)
            assertEquals(c.name, CapDecision.ALLOWED, caps.tryConsume())
        }
    }

    @Test
    fun `one in N counts attempts and only those that passed day cap and interval`() {
        for ((n, expected) in mapOf(1 to "AAAA", 2 to "OAOA", 3 to "OOAO").mapValues { it.value.map { c -> if (c == 'A') CapDecision.ALLOWED else CapDecision.ONE_IN_N } }) {
            val caps = caps(InterstitialOptions(minIntervalMs = 0, oneInN = n), store = FakeCapStore())
            assertEquals("N=$n", expected, List(4) { caps.tryConsume() })
        }

        val withInterval = caps(InterstitialOptions(minIntervalMs = 50_000, oneInN = 2), store = FakeCapStore())
        clock.set(1_000_000)
        withInterval.recordShown()
        clock.advanceBy(10_000)
        assertEquals(CapDecision.INTERVAL, withInterval.tryConsume()) // not counted
        clock.advanceBy(50_000)
        assertEquals(CapDecision.ONE_IN_N, withInterval.tryConsume()) // first counted attempt
        assertEquals(CapDecision.ALLOWED, withInterval.tryConsume())
    }

    @Test
    fun `counters survive a restart through the store`() {
        val options = InterstitialOptions(minIntervalMs = 0, dailyCap = 1)
        clock.set(5_000)
        caps(options).recordShown()
        assertEquals(CapDecision.DAILY_CAP, caps(options).tryConsume())
    }

    @Test
    fun `restart keeps the count the same day and a new day resets it`() {
        val options = InterstitialOptions(minIntervalMs = 0, dailyCap = 1)
        clock.set(DAY_MS - 5_000)
        caps(options).recordShown()
        assertEquals(1, store.current.dayCount)

        clock.advanceBy(5_000) // restarted after midnight
        assertEquals(CapDecision.ALLOWED, caps(options).tryConsume())
    }

    @Test
    fun `clock moved back keeps the stored day - count never negative, cap not dodged, no throw`() {
        val options = InterstitialOptions(minIntervalMs = 50_000, dailyCap = 1)
        clock.set(3 * DAY_MS + 1_000)
        val caps = caps(options)
        caps.recordShown()

        clock.set(DAY_MS) // user moves the clock two days back
        assertEquals(CapDecision.DAILY_CAP, caps.tryConsume())
        caps.recordShown()
        assertEquals(2, store.current.dayCount)
        assertEquals(3L, store.current.dayBucket)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
