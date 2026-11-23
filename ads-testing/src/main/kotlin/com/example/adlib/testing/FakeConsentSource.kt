package com.example.adlib.testing

import android.app.Activity
import com.example.adlib.core.consent.ConsentError
import com.example.adlib.core.consent.ConsentFormHandle
import com.example.adlib.core.consent.ConsentSource
import com.example.adlib.core.consent.FormLoadResult

/**
 * Manually driven UMP fake: every call stays pending until the test answers it, so "form on screen
 * longer than the timeout", "update fails but canRequestAds is true" and "late answer" are easy.
 * Set the flags ([canRequestAds], [formRequired], ...) BEFORE answering, like UMP updates them.
 */
class FakeConsentSource(
    var canRequestAds: Boolean = false,
    var privacyOptionsRequired: Boolean = false,
    var formRequired: Boolean = false,
) : ConsentSource {
    var updateCalls = 0
        private set
    var formLoadCalls = 0
        private set
    var formShowCalls = 0
        private set
    var privacyOptionsCalls = 0
        private set

    private var pendingUpdate: ((ConsentError?) -> Unit)? = null
    private var pendingLoad: ((FormLoadResult) -> Unit)? = null
    private var pendingDismiss: ((ConsentError?) -> Unit)? = null
    private var pendingPrivacy: ((ConsentError?) -> Unit)? = null

    val isUpdatePending get() = pendingUpdate != null
    val isFormLoadPending get() = pendingLoad != null
    val isFormOnScreen get() = pendingDismiss != null

    override fun canRequestAds() = canRequestAds

    override fun isPrivacyOptionsRequired() = privacyOptionsRequired

    override fun isFormRequired() = formRequired

    override fun requestInfoUpdate(activity: Activity, onDone: (ConsentError?) -> Unit) {
        updateCalls++
        pendingUpdate = onDone
    }

    override fun loadForm(activity: Activity, onDone: (FormLoadResult) -> Unit) {
        formLoadCalls++
        pendingLoad = onDone
    }

    override fun showPrivacyOptionsForm(activity: Activity, onDismissed: (ConsentError?) -> Unit) {
        privacyOptionsCalls++
        pendingPrivacy = onDismissed
    }

    fun answerUpdate(error: ConsentError? = null) = take(::pendingUpdate, "update")(error)

    fun answerFormLoad(error: ConsentError? = null) {
        val result = if (error != null) {
            FormLoadResult.Failed(error)
        } else {
            FormLoadResult.Loaded(
                ConsentFormHandle { _, onDismissed ->
                    formShowCalls++
                    pendingDismiss = onDismissed
                },
            )
        }
        take(::pendingLoad, "form load")(result)
    }

    /** The user closes the consent form. */
    fun dismissForm(error: ConsentError? = null) = take(::pendingDismiss, "form show")(error)

    fun dismissPrivacyOptions(error: ConsentError? = null) = take(::pendingPrivacy, "privacy options")(error)

    private fun <T> take(slot: kotlin.reflect.KMutableProperty0<T?>, what: String): T {
        val value = checkNotNull(slot.get()) { "no pending $what" }
        slot.set(null)
        return value
    }
}

/** Counts initialize calls; [complete] fires the pending listener (also from another thread if wanted). */
class FakeSdkInitializer : com.example.adlib.core.bootstrap.SdkInitializer {
    var calls = 0
        private set
    private var pending: (() -> Unit)? = null

    override fun initialize(onComplete: () -> Unit) {
        calls++
        pending = onComplete
    }

    fun complete() {
        val listener = checkNotNull(pending) { "initialize was not called" }
        pending = null
        listener()
    }
}
