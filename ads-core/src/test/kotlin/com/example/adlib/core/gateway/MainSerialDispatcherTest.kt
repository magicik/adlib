package com.example.adlib.core.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainSerialDispatcherTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = MainSerialDispatcher(StandardTestDispatcher(scheduler))

    @Test
    fun `callbacks run in posting order and never inline, even when posted from a callback`() {
        val log = mutableListOf<String>()
        dispatcher.post {
            log += "a-start"
            dispatcher.post { log += "nested" }
            log += "a-end"
        }
        dispatcher.post { log += "b" }
        assertEquals(emptyList<String>(), log)

        scheduler.advanceUntilIdle()

        assertEquals(listOf("a-start", "a-end", "b", "nested"), log)
    }
}
