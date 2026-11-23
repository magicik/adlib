package com.example.adlib.core.events

import com.example.adlib.core.gateway.MainSerialDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdsEventBusTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = MainSerialDispatcher(StandardTestDispatcher(scheduler))

    @Test
    fun `listener is called via the dispatcher, never inline`() {
        val got = mutableListOf<AdsEvent>()
        val bus = AdsEventBus(dispatcher, AdsEventListener { got += it })
        bus.emit(AdsEvent.Shown("p"))
        assertEquals(0, got.size)
        scheduler.advanceUntilIdle()
        assertEquals(listOf<AdsEvent>(AdsEvent.Shown("p")), got)
    }

    @Test
    fun `a throwing listener does not stop later events`() {
        var calls = 0
        val bus = AdsEventBus(dispatcher, AdsEventListener { calls++; error("boom") })
        bus.emit(AdsEvent.Shown("p"))
        bus.emit(AdsEvent.Clicked("p"))
        scheduler.advanceUntilIdle()
        assertEquals(2, calls)
    }
}
