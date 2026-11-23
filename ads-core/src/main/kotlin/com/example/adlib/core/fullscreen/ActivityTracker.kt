package com.example.adlib.core.fullscreen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

/** The app's Activity lifecycle as the lib sees it. Holds no Activity strongly (v3 7). */
interface ActivityTracker {
    /**
     * How many times an Activity of the app that is NOT an ad Activity reached ON_RESUME.
     * Only increases; a reader compares against a value it saw earlier.
     */
    val appResumes: StateFlow<Long>

    /** How many non-ad Activities of the app are STARTED right now (0 = app in background or between hosts). */
    val startedAppActivities: StateFlow<Int>

    /**
     * The top Activity: the non-ad Activity of the app that is RESUMED right now, or null (paused, an ad
     * or another app on top, background). Set before [appResumes] counts its resume, so
     * `appResumes.first { resumedActivity != null }` waits for it without a race. Weakly held.
     */
    val resumedActivity: Activity?
}

/**
 * Whether the app is in the foreground: at least one of its Activities, ad Activities included (an ad
 * on top is not a trip to the background), is STARTED. A configuration change (rotation) is not a
 * background trip.
 */
interface ForegroundSource {
    val isForeground: StateFlow<Boolean>
}

/** Ad Activities of the SDK (checked in the 1.7.0 AAR manifest: `...sdk.common.AdActivity`). */
private val AD_ACTIVITY_PREFIXES = listOf("com.google.android.libraries.ads.", "com.google.android.gms.ads.")

internal fun isAdActivity(className: String): Boolean = AD_ACTIVITY_PREFIXES.any { className.startsWith(it) }

/**
 * The process's one [ActivityTracker] and [ForegroundSource] ([of]), fed by
 * [Application.ActivityLifecycleCallbacks]; keeps counters and a weak top Activity only.
 */
internal class AppActivityTracker private constructor(application: Application) : ActivityTracker, ForegroundSource {
    private val resumes = MutableStateFlow(0L)
    override val appResumes: StateFlow<Long> = resumes.asStateFlow()
    private val started = MutableStateFlow(0)
    override val startedAppActivities: StateFlow<Int> = started.asStateFlow()
    private var top: WeakReference<Activity>? = null
    override val resumedActivity: Activity? get() = top?.get()

    /** Every started Activity, ad ones included; a stop for a configuration change is kept until the new one starts. */
    private var startedAll = 0
    private var stoppedForConfigChange = 0
    private val foreground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = foreground.asStateFlow()

    private val destroyedListeners = mutableListOf<(Activity) -> Unit>()

    /** Called on the main thread for every destroyed Activity (e.g. `ConsentGate.onActivityDestroyed`). */
    fun addDestroyedListener(listener: (Activity) -> Unit) {
        destroyedListeners += listener
    }

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (isAdActivity(activity.javaClass.name)) return
                    top = WeakReference(activity)
                    resumes.update { it + 1 }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) {
                    if (stoppedForConfigChange > 0) stoppedForConfigChange-- else startedAll++
                    foreground.value = true
                    if (!isAdActivity(activity.javaClass.name)) started.update { it + 1 }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (top?.get() === activity) top = null
                }

                override fun onActivityStopped(activity: Activity) {
                    if (activity.isChangingConfigurations) stoppedForConfigChange++ else startedAll = maxOf(0, startedAll - 1)
                    foreground.value = startedAll > 0
                    if (!isAdActivity(activity.javaClass.name)) started.update { maxOf(0, it - 1) }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (top?.get() === activity) top = null
                    destroyedListeners.forEach { it(activity) }
                }
            },
        )
    }

    companion object {
        @Volatile
        private var instance: AppActivityTracker? = null

        /** One tracker per process: registers its callbacks once, however many features ask for it. */
        fun of(application: Application): AppActivityTracker =
            instance ?: synchronized(this) { instance ?: AppActivityTracker(application).also { instance = it } }
    }
}
