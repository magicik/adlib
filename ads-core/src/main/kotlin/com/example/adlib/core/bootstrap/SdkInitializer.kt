package com.example.adlib.core.bootstrap

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlin.concurrent.thread

/** Starts the ads SDK. [onComplete] may be called on any thread, at most once. */
fun interface SdkInitializer {
    fun initialize(onComplete: () -> Unit)
}

/**
 * Calls MobileAds.initialize on a dedicated thread (it is @WorkerThread). The listener fires when
 * adapters finished or after the SDK's own 30s timeout. [appId] defaults to the manifest meta-data
 * `com.google.android.gms.ads.APPLICATION_ID` (UMP needs it there too). [configure] runs before
 * build(), e.g. to set the RequestConfiguration (age-restricted treatment must be set BEFORE init).
 */
class MobileAdsInitializer(
    context: Context,
    appId: String? = null,
    private val configure: InitializationConfig.Builder.() -> Unit = {},
) : SdkInitializer {
    private val appContext = context.applicationContext
    private val appId: String = appId ?: readManifestAppId(appContext)

    override fun initialize(onComplete: () -> Unit) {
        thread(name = "AdLib-init", isDaemon = true) {
            val config = InitializationConfig.Builder(appId).apply(configure).build()
            MobileAds.initialize(appContext, config) { onComplete() }
        }
    }

    private companion object {
        const val APP_ID_KEY = "com.google.android.gms.ads.APPLICATION_ID"

        @Suppress("DEPRECATION") // ApplicationInfoFlags overload is API 33+; minSdk is 24.
        fun readManifestAppId(context: Context): String {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val id = info.metaData?.getString(APP_ID_KEY)
            require(!id.isNullOrBlank()) { "Missing <meta-data android:name=\"$APP_ID_KEY\"> in AndroidManifest.xml" }
            return id
        }
    }
}
