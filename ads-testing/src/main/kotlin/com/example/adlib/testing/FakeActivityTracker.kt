package com.example.adlib.testing

import com.example.adlib.core.fullscreen.ActivityTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Manually driven [ActivityTracker]. */
class FakeActivityTracker : ActivityTracker {
    private val resumes = MutableStateFlow(0L)
    override val appResumes: StateFlow<Long> = resumes.asStateFlow()

    /** A non-ad Activity of the app reached ON_RESUME. */
    fun resumeAppActivity() {
        resumes.value += 1
    }
}
