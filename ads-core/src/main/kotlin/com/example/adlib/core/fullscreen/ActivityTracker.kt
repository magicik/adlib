package com.example.adlib.core.fullscreen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The app's Activity lifecycle as the lib sees it. Holds no Activity (v3 7). */
interface ActivityTracker {
    /**
     * How many times an Activity of the app that is NOT an ad Activity reached ON_RESUME.
     * Only increases; a reader compares against a value it saw earlier.
     */
    val appResumes: StateFlow<Long>

    /** How many non-ad Activities of the app are STARTED right now (0 = app in background or between hosts). */
    val startedAppActivities: StateFlow<Int>
}

/** Ad Activities of the SDK (checked in the 1.7.0 AAR manifest: `...sdk.common.AdActivity`). */
private val AD_ACTIVITY_PREFIXES = listOf("com.google.android.libraries.ads.", "com.google.android.gms.ads.")

internal fun isAdActivity(className: String): Boolean = AD_ACTIVITY_PREFIXES.any { className.startsWith(it) }

/** [ActivityTracker] fed by [Application.ActivityLifecycleCallbacks]; keeps only a counter. */
internal class AppActivityTracker(application: Application) : ActivityTracker {
    private val resumes = MutableStateFlow(0L)
    override val appResumes: StateFlow<Long> = resumes.asStateFlow()
    private val started = MutableStateFlow(0)
    override val startedAppActivities: StateFlow<Int> = started.asStateFlow()

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (!isAdActivity(activity.javaClass.name)) resumes.update { it + 1 }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) {
                    if (!isAdActivity(activity.javaClass.name)) started.update { it + 1 }
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    if (!isAdActivity(activity.javaClass.name)) started.update { maxOf(0, it - 1) }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
