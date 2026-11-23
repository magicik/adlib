package com.example.adlib.core.fullscreen

import android.app.Activity
import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActivityTrackerTest {
    @Test
    fun `one tracker per process - top Activity, foreground across rotation`() {
        val callbacks = slot<Application.ActivityLifecycleCallbacks>()
        val app = mockk<Application>(relaxed = true)
        every { app.registerActivityLifecycleCallbacks(capture(callbacks)) } returns Unit
        val tracker = AppActivityTracker.of(app)
        assertSame(tracker, AppActivityTracker.of(app))
        verify(exactly = 1) { app.registerActivityLifecycleCallbacks(any()) }
        val cb = callbacks.captured
        val destroyed = mutableListOf<Activity>()
        tracker.addDestroyedListener { destroyed += it }

        val host = mockk<Activity>(relaxed = true)
        cb.onActivityStarted(host)
        assertTrue(tracker.isForeground.value)
        assertNull("started is not resumed", tracker.resumedActivity)
        cb.onActivityResumed(host)
        assertSame(host, tracker.resumedActivity)
        assertEquals(1L, tracker.appResumes.value)

        // Rotation: the stop for a configuration change is no trip to the background.
        every { host.isChangingConfigurations } returns true
        cb.onActivityPaused(host)
        cb.onActivityStopped(host)
        cb.onActivityDestroyed(host)
        assertTrue(tracker.isForeground.value)
        assertNull(tracker.resumedActivity)
        assertEquals(listOf(host), destroyed)
        val rotated = mockk<Activity>(relaxed = true)
        cb.onActivityStarted(rotated)
        cb.onActivityResumed(rotated)
        assertSame(rotated, tracker.resumedActivity)

        // Home: the last Activity stops for real.
        cb.onActivityPaused(rotated)
        cb.onActivityStopped(rotated)
        assertFalse(tracker.isForeground.value)
        assertEquals(0, tracker.startedAppActivities.value)
    }
}
