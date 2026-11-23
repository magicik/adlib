package com.example.adlib.core.bootstrap

import com.example.adlib.testing.FakeSdkInitializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdsBootstrapTest {
    private val initWait = AdsBootstrap.DEFAULT_INIT_WAIT_MS
    private val configWait = AdsBootstrap.DEFAULT_CONFIG_WAIT_MS

    private fun TestScope.bootstrap(
        consent: MutableStateFlow<Boolean>,
        init: FakeSdkInitializer,
        awaitConfig: suspend () -> Unit = {},
    ) = AdsBootstrap(consent, init, backgroundScope, awaitConfig)

    @Test
    fun `consent from previous session starts init at T0, once`() = runTest {
        val consent = MutableStateFlow(true)
        val init = FakeSdkInitializer()
        val boot = bootstrap(consent, init)
        runCurrent()
        assertEquals(1, init.calls)

        val ready = async { boot.awaitReadyToLoad() }
        runCurrent()
        init.complete()
        runCurrent()
        assertTrue(ready.await())
        assertTrue(boot.sdkInitialized.value)

        consent.value = false
        runCurrent()
        consent.value = true
        runCurrent()
        assertEquals(1, init.calls)
    }

    @Test
    fun `no consent - no init and waiters get false immediately`() = runTest {
        val init = FakeSdkInitializer()
        val boot = bootstrap(MutableStateFlow(false), init)
        runCurrent()
        assertFalse(boot.awaitReadyToLoad())
        assertEquals(0, currentTime)
        assertEquals(0, init.calls)
    }

    @Test
    fun `consent arriving late starts init then`() = runTest {
        val consent = MutableStateFlow(false)
        val init = FakeSdkInitializer()
        val boot = bootstrap(consent, init)
        advanceTimeBy(30_000)
        assertEquals(0, init.calls)

        consent.value = true
        runCurrent()
        assertEquals(1, init.calls)
        init.complete()
        runCurrent()
        assertTrue(boot.awaitReadyToLoad())
    }

    @Test
    fun `slow init - waiter gives up after init wait, later waiters succeed`() = runTest {
        val init = FakeSdkInitializer()
        val boot = bootstrap(MutableStateFlow(true), init)
        // Config cap must not be what ends the wait: let it pass first.
        advanceTimeBy(configWait + 1)

        val start = currentTime
        val first = async { boot.awaitReadyToLoad() to currentTime - start }
        advanceTimeBy(initWait + 1)
        val (ready, waited) = first.await()
        assertFalse(ready)
        assertEquals(initWait, waited)

        init.complete()
        runCurrent()
        assertTrue(boot.awaitReadyToLoad())
        assertEquals(1, init.calls)
    }

    @Test
    fun `config wait is capped from T0 and errors fall back`() = runTest {
        // awaitConfig behaviour -> time at which the first ready check returns
        val never: suspend () -> Unit = { awaitCancellation() }
        val twoSeconds: suspend () -> Unit = { kotlinx.coroutines.delay(2_000) }
        val fails: suspend () -> Unit = { error("remote config failed") }
        val cases = listOf(never to configWait, twoSeconds to 2_000L, fails to 0L)
        for ((i, case) in cases.withIndex()) {
            val (awaitConfig, expectedReturn) = case
            val t0 = currentTime
            val init = FakeSdkInitializer()
            val boot = bootstrap(MutableStateFlow(true), init, awaitConfig)
            runCurrent()
            init.complete()
            runCurrent()
            val ready = async { boot.awaitReadyToLoad() }
            val done = CompletableDeferred<Long>()
            async { ready.await(); done.complete(currentTime - t0) }
            advanceTimeBy(configWait + 1)
            runCurrent()
            assertTrue("case $i", ready.await())
            assertEquals("case $i", expectedReturn, done.await())
        }
    }
}
