package com.example.adlib.core.consent

import android.app.Activity
import com.example.adlib.testing.FakeConsentSource
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentGateTest {
    private val activity = mockk<Activity>(relaxed = true)
    private val timeout = ConsentGate.DEFAULT_NETWORK_TIMEOUT_MS

    private fun TestScope.gate(source: FakeConsentSource) = ConsentGate(source, backgroundScope)

    @Test
    fun `initial flags come from the previous session`() = runTest {
        for ((can, privacy) in listOf(true to true, true to false, false to false)) {
            val gate = gate(FakeConsentSource(canRequestAds = can, privacyOptionsRequired = privacy))
            assertEquals(can, gate.canRequestAds.value)
            assertEquals(privacy, gate.privacyOptionsRequired.value)
        }
    }

    @Test
    fun `update without form refreshes flags and is not repeated in the process`() = runTest {
        val source = FakeConsentSource()
        val gate = gate(source)
        val result = async { gate.awaitGather(activity) }
        runCurrent()
        source.canRequestAds = true
        source.privacyOptionsRequired = true
        source.answerUpdate()
        runCurrent()

        assertEquals(ConsentOutcome(canRequestAds = true), result.await())
        assertTrue(gate.canRequestAds.value)
        assertTrue(gate.privacyOptionsRequired.value)

        gate.awaitGather(activity)
        assertEquals(1, source.updateCalls)
    }

    @Test
    fun `form on screen longer than the timeout is not cut off`() = runTest {
        val source = FakeConsentSource(formRequired = true)
        val gate = gate(source)
        val result = async { gate.awaitGather(activity) }
        runCurrent()
        source.answerUpdate()
        runCurrent()
        source.answerFormLoad()
        runCurrent()
        assertTrue(source.isFormOnScreen)
        assertTrue(gate.formShowing.value)

        advanceTimeBy(timeout * 10)
        assertFalse(result.isCompleted)

        source.canRequestAds = true
        source.dismissForm()
        runCurrent()
        assertEquals(ConsentOutcome(canRequestAds = true, formShown = true), result.await())
        assertFalse(gate.formShowing.value)
        assertTrue(gate.canRequestAds.value)
    }

    @Test
    fun `network steps time out and a late update still turns ads on`() = runTest {
        // step that hangs -> expected calls
        val cases = listOf("update", "formLoad")
        for (case in cases) {
            val source = FakeConsentSource(formRequired = true)
            val gate = gate(source)
            val result = async { gate.awaitGather(activity) }
            runCurrent()
            if (case == "formLoad") {
                source.answerUpdate()
                runCurrent()
            }
            advanceTimeBy(timeout - 1)
            runCurrent()
            assertFalse(case, result.isCompleted)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(case, ConsentOutcome(canRequestAds = false, timedOut = true), result.await())
            assertEquals(case, 0, source.formShowCalls)

            if (case == "update") {
                source.canRequestAds = true
                source.answerUpdate()
                runCurrent()
                assertTrue("late update refreshes", gate.canRequestAds.value)
            } else {
                source.answerFormLoad()
                runCurrent()
                assertEquals("late form is never shown", 0, source.formShowCalls)
            }
        }
    }

    @Test
    fun `update error keeps the previous session consent and retries on next gather`() = runTest {
        val source = FakeConsentSource(canRequestAds = true)
        val gate = gate(source)
        val error = ConsentError(2, "network")
        val first = async { gate.awaitGather(activity) }
        runCurrent()
        source.answerUpdate(error)
        runCurrent()
        assertEquals(ConsentOutcome(canRequestAds = true, error = error), first.await())

        val second = async { gate.awaitGather(activity) }
        runCurrent()
        assertEquals(2, source.updateCalls)
        source.answerUpdate()
        runCurrent()
        assertEquals(ConsentOutcome(canRequestAds = true), second.await())
    }

    @Test
    fun `form load or show errors are reported and the gather can run again`() = runTest {
        val error = ConsentError(1, "boom")
        for (failShow in listOf(false, true)) {
            val source = FakeConsentSource(formRequired = true)
            val gate = gate(source)
            val result = async { gate.awaitGather(activity) }
            runCurrent()
            source.answerUpdate()
            runCurrent()
            source.answerFormLoad(if (failShow) null else error)
            runCurrent()
            if (failShow) {
                source.dismissForm(error)
                runCurrent()
            }
            assertEquals(ConsentOutcome(canRequestAds = false, error = error, formShown = failShow), result.await())
            assertFalse(gate.formShowing.value)

            async { gate.awaitGather(activity) }
            runCurrent()
            assertEquals(2, source.updateCalls)
        }
    }

    @Test
    fun `concurrent gathers share one update`() = runTest {
        val source = FakeConsentSource()
        val gate = gate(source)
        val a = async { gate.awaitGather(activity) }
        val b = async { gate.awaitGather(activity) }
        runCurrent()
        source.canRequestAds = true
        source.answerUpdate()
        runCurrent()
        assertEquals(1, source.updateCalls)
        assertEquals(a.await(), b.await())
    }

    @Test
    fun `privacy options form marks form showing and refreshes after`() = runTest {
        val source = FakeConsentSource(canRequestAds = true, privacyOptionsRequired = true)
        val gate = gate(source)
        var done: ConsentError? = ConsentError(-1, "not called")
        gate.showPrivacyOptions(activity) { done = it }
        runCurrent()
        assertTrue(gate.formShowing.value)

        source.canRequestAds = false
        source.dismissPrivacyOptions()
        runCurrent()
        assertEquals(null, done)
        assertFalse(gate.formShowing.value)
        assertFalse(gate.canRequestAds.value)
    }

    @Test
    fun `form whose host is destroyed without a dismiss stops formShowing and gather can run again`() = runTest {
        val source = FakeConsentSource(formRequired = true, canRequestAds = true, privacyOptionsRequired = true)
        val gate = gate(source)
        val other = mockk<Activity>(relaxed = true)

        // Consent form.
        val result = async { gate.awaitGather(activity) }
        runCurrent()
        source.answerUpdate()
        runCurrent()
        source.answerFormLoad()
        runCurrent()
        gate.onActivityDestroyed(other)
        runCurrent()
        assertTrue("another Activity does not end it", gate.formShowing.value)

        gate.onActivityDestroyed(activity)
        runCurrent()
        assertFalse(gate.formShowing.value)
        assertEquals(ConsentOutcome(true, error = ConsentGate.HOST_DESTROYED, formShown = true), result.await())
        source.dismissForm() // a late dismiss is harmless
        runCurrent()
        val again = async { gate.awaitGather(activity) }
        runCurrent()
        assertEquals("not finished cleanly: runs again", 2, source.updateCalls)
        again.cancel()

        // Privacy options form.
        var done: ConsentError? = null
        gate.showPrivacyOptions(activity) { done = it }
        runCurrent()
        assertTrue(gate.formShowing.value)
        gate.onActivityDestroyed(activity)
        runCurrent()
        assertFalse(gate.formShowing.value)
        assertEquals(ConsentGate.HOST_DESTROYED, done)
    }
}
