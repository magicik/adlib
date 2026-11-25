package com.example.adlib.core.state

import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdsStateTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = MainSerialDispatcher(StandardTestDispatcher(scheduler))
    private val received = mutableListOf<AdsEvent>()
    private val bus = AdsEventBus(dispatcher, AdsEventListener { received += it })
    private val placement = Placement("p", AdFormat.INTERSTITIAL, default = PlacementConfig())
    private var json: String? = null
    private var consent = true

    private fun state(premium: Boolean = false) =
        AdsState(premium, { json }, bus) { consent }

    @Test
    fun `isAdAllowed over premium, kill switch, config enable and consent`() {
        // premium, kill, enableJson, consent -> allowed
        val cases = listOf(
            listOf(false, false, true, true, true),
            listOf(true, false, true, true, false),
            listOf(false, true, true, true, false),
            listOf(false, false, false, true, false),
            listOf(false, false, true, false, false),
        )
        for ((premium, kill, enable, canRequest, expected) in cases) {
            json = """{"enable":$enable}"""
            consent = canRequest as Boolean
            val s = state(premium as Boolean).also { it.setKillSwitch(kill as Boolean) }
            assertEquals("$cases", expected, s.isAdAllowed(placement))
        }
    }

    @Test
    fun `config is read from the provider on every lookup`() {
        val s = state()
        json = """{"enable":true}"""
        assertTrue(s.isAdAllowed(placement))
        json = """{"enable":false}"""
        assertFalse(s.isAdAllowed(placement))
    }

    @Test
    fun `premium starts from the supplied value and updates the flow`() {
        val s = state(premium = true)
        assertTrue(s.isPremium.value)
        s.setPremium(false)
        assertFalse(s.isPremium.value)
    }

    @Test
    fun `parser warnings reach the listener as ConfigWarning`() {
        json = """{"list_ads":[]}"""
        state().config(placement)
        scheduler.advanceUntilIdle()
        assertTrue(received.single() is AdsEvent.ConfigWarning)
        assertEquals("p", received.single().placement)
    }

    @Test
    fun `same broken json warns once across lookups, changed json is re-parsed`() {
        val s = state()
        json = """{"list_ads":[]}"""
        s.config(placement)
        s.isAdAllowed(placement)
        s.config(placement)
        scheduler.advanceUntilIdle()
        assertEquals(1, received.size)
        json = """{"list_ads":[{}]}"""
        s.config(placement)
        s.config(placement)
        scheduler.advanceUntilIdle()
        assertEquals(2, received.size)

        json = null
        s.config(placement)
        json = """{"list_ads":[]}"""
        s.config(placement)
        scheduler.advanceUntilIdle()
        assertEquals(3, received.size)
    }
}
