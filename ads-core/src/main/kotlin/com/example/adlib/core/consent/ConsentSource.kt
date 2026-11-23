package com.example.adlib.core.consent

import android.app.Activity

/** UMP error passed through unchanged ([code] = FormError.getErrorCode()). */
data class ConsentError(val code: Int, val message: String)

sealed interface FormLoadResult {
    data class Loaded(val form: ConsentFormHandle) : FormLoadResult

    data class Failed(val error: ConsentError) : FormLoadResult
}

fun interface ConsentFormHandle {
    /** [onDismissed] gets null when the user closed the form normally. */
    fun show(activity: Activity, onDismissed: (ConsentError?) -> Unit)
}

/**
 * Isolates UMP so [ConsentGate] can be tested with a fake. Callbacks may arrive on any thread;
 * the gate re-posts them. Each callback is invoked at most once per call.
 *
 * Load and show of the consent form are separate (not loadAndShowConsentFormIfRequired) so the
 * 10s timeout can cover the network part only, never the time the form is on screen (design v3 5).
 */
interface ConsentSource {
    fun canRequestAds(): Boolean

    fun isPrivacyOptionsRequired(): Boolean

    /** Valid after a successful [requestInfoUpdate]: consent status REQUIRED and a form is available. */
    fun isFormRequired(): Boolean

    fun requestInfoUpdate(activity: Activity, onDone: (ConsentError?) -> Unit)

    fun loadForm(activity: Activity, onDone: (FormLoadResult) -> Unit)

    fun showPrivacyOptionsForm(activity: Activity, onDismissed: (ConsentError?) -> Unit)
}
