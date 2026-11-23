package com.example.adlib.core.consent

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one [ConsentGate.gather]. */
data class ConsentOutcome(
    val canRequestAds: Boolean,
    /** UMP error of the update, form load or form show, if any. */
    val error: ConsentError? = null,
    /** The update or the form load did not answer within the network timeout. */
    val timedOut: Boolean = false,
    val formShown: Boolean = false,
)

/**
 * Process-level consent state (design v3 section 5). Must be driven from ONE serial dispatcher
 * ([scope]); source callbacks from any thread are re-posted onto it.
 *
 * - [canRequestAds] starts from the previous session's value, so the SDK can init before UMP answers.
 * - [networkTimeoutMs] covers requestInfoUpdate and loading the form, NOT the form on screen.
 * - A late update still refreshes the flags (UMP answering after the timeout can turn ads on).
 * - A gather that finished cleanly is not repeated in this process (recreate keeps the result, R18);
 *   one that failed or timed out runs again on the next call.
 */
class ConsentGate(
    private val source: ConsentSource,
    private val scope: CoroutineScope,
    private val networkTimeoutMs: Long = DEFAULT_NETWORK_TIMEOUT_MS,
) {
    private val _canRequestAds = MutableStateFlow(source.canRequestAds())
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(source.isPrivacyOptionsRequired())
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /** True while a consent or privacy options form is on screen: pause App Open, no full-screen loads. */
    private val _formShowing = MutableStateFlow(false)
    val formShowing: StateFlow<Boolean> = _formShowing.asStateFlow()

    private var inFlight: Deferred<ConsentOutcome>? = null
    private var completed: ConsentOutcome? = null

    fun gather(activity: Activity, onDone: (ConsentOutcome) -> Unit = {}) {
        scope.launch { onDone(awaitGather(activity)) }
    }

    /** Joins a gather already running instead of starting a second one. */
    suspend fun awaitGather(activity: Activity): ConsentOutcome {
        completed?.let { return it.copy(canRequestAds = refresh()) }
        val running = inFlight ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                runGather(activity)
            } finally {
                inFlight = null
            }
        }.also {
            inFlight = it
            it.start()
        }
        return running.await()
    }

    fun showPrivacyOptions(activity: Activity, onDone: (ConsentError?) -> Unit = {}) {
        scope.launch {
            val error = showing { awaitOnce<ConsentError?> { source.showPrivacyOptionsForm(activity, it) } }
            refresh()
            onDone(error)
        }
    }

    private suspend fun runGather(activity: Activity): ConsentOutcome {
        val update = withTimeoutOrNull(networkTimeoutMs) {
            awaitOnce<ConsentError?> { source.requestInfoUpdate(activity, it) }
        } ?: return ConsentOutcome(refresh(), timedOut = true) // a late answer still refreshes (awaitOnce)
        val updateError = update.value
        if (updateError != null) return ConsentOutcome(refresh(), error = updateError)

        if (!source.isFormRequired()) return finish(ConsentOutcome(refresh()))

        val load = withTimeoutOrNull(networkTimeoutMs) { awaitOnce<FormLoadResult> { source.loadForm(activity, it) } }
            ?: return ConsentOutcome(refresh(), timedOut = true)
        val form = when (val result = load.value) {
            is FormLoadResult.Failed -> return ConsentOutcome(refresh(), error = result.error)
            is FormLoadResult.Loaded -> result.form
        }
        val showError = showing { awaitOnce<ConsentError?> { form.show(activity, it) } }
        val outcome = ConsentOutcome(refresh(), error = showError, formShown = true)
        return if (showError == null) finish(outcome) else outcome
    }

    private fun finish(outcome: ConsentOutcome): ConsentOutcome = outcome.also { completed = it }

    private suspend fun <T> showing(block: suspend () -> Box<T>): T {
        _formShowing.value = true
        return try {
            block().value
        } finally {
            _formShowing.value = false
        }
    }

    private fun refresh(): Boolean {
        _privacyOptionsRequired.value = source.isPrivacyOptionsRequired()
        return source.canRequestAds().also { _canRequestAds.value = it }
    }

    /**
     * Bridges a one-shot callback into [scope]. The callback is re-posted (thread unknown), a second
     * invocation is ignored, and an answer arriving after the waiter gave up still triggers [refresh].
     */
    private suspend fun <T> awaitOnce(start: ((T) -> Unit) -> Unit): Box<T> {
        val result = CompletableDeferred<Box<T>>()
        val fired = AtomicBoolean(false)
        start { value ->
            if (fired.compareAndSet(false, true)) {
                scope.launch {
                    refresh()
                    result.complete(Box(value))
                }
            }
        }
        return result.await()
    }

    /** Wraps nullable results so "answered with null" differs from "timed out" (withTimeoutOrNull). */
    private class Box<T>(val value: T)

    companion object {
        const val DEFAULT_NETWORK_TIMEOUT_MS = 10_000L
    }
}
