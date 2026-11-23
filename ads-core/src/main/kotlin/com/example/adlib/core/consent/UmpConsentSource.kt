package com.example.adlib.core.consent

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * [ConsentSource] backed by UMP 4.0.0. [parameters] is built for every update so the app can
 * set debug geography / test device hashes / under-age tag.
 */
class UmpConsentSource(
    context: Context,
    private val parameters: () -> ConsentRequestParameters = { ConsentRequestParameters.Builder().build() },
) : ConsentSource {
    private val appContext = context.applicationContext
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(appContext)

    override fun canRequestAds(): Boolean = info.canRequestAds()

    override fun isPrivacyOptionsRequired(): Boolean =
        info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun isFormRequired(): Boolean =
        info.consentStatus == ConsentInformation.ConsentStatus.REQUIRED && info.isConsentFormAvailable

    override fun requestInfoUpdate(activity: Activity, onDone: (ConsentError?) -> Unit) {
        info.requestConsentInfoUpdate(
            activity,
            parameters(),
            { onDone(null) },
            { error -> onDone(error.toConsentError()) },
        )
    }

    override fun loadForm(activity: Activity, onDone: (FormLoadResult) -> Unit) {
        UserMessagingPlatform.loadConsentForm(
            appContext,
            { form ->
                onDone(
                    FormLoadResult.Loaded(
                        ConsentFormHandle { host, onDismissed ->
                            form.show(host) { error -> onDismissed(error?.toConsentError()) }
                        },
                    ),
                )
            },
            { error -> onDone(FormLoadResult.Failed(error.toConsentError())) },
        )
    }

    override fun showPrivacyOptionsForm(activity: Activity, onDismissed: (ConsentError?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDismissed(error?.toConsentError()) }
    }
}

private fun FormError.toConsentError() = ConsentError(errorCode, message)
