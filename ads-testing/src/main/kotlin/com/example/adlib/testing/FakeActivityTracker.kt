package com.example.adlib.testing

import android.app.Activity
import com.example.adlib.core.fullscreen.ActivityTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Manually driven [ActivityTracker]. [initialStartedHosts] = 1: one app Activity is STARTED unless a test says otherwise. */
class FakeActivityTracker(initialStartedHosts: Int = 1) : ActivityTracker {
    private val resumes = MutableStateFlow(0L)
    override val appResumes: StateFlow<Long> = resumes.asStateFlow()

    private val started = MutableStateFlow(initialStartedHosts)
    override val startedAppActivities: StateFlow<Int> = started.asStateFlow()

    override var resumedActivity: Activity? = null
        private set

    fun startHost() {
        started.value += 1
    }

    fun stopHost() {
        started.value = maxOf(0, started.value - 1)
    }

    /** A non-ad Activity of the app reached ON_RESUME; with [activity] it also becomes the top one. */
    fun resumeAppActivity(activity: Activity? = null) {
        if (activity != null) resumedActivity = activity
        resumes.value += 1
    }

    /** The top Activity paused (another Activity, an ad or the background comes next). */
    fun pauseAppActivity() {
        resumedActivity = null
    }
}
