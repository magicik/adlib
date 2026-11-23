package com.example.adlib.core.appopen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.lang.ref.WeakReference

/**
 * Default [AppOpenOverlay]: dark scrim + spinner over the host content, touches and Back swallowed while
 * visible. [content] lets the app replace the view; it must be a plain View (no Compose in ads-core).
 */
class DefaultAppOpenOverlay(
    private val content: (Activity) -> View = ::defaultContent,
) : AppOpenOverlay {
    override fun show(host: Activity): AppOpenOverlay.Shown {
        val surface = AndroidSurface(host, content)
        val session = OverlaySession(surface)
        surface.onHostGone = session::onHostGone
        return session.start()
    }

    private companion object {
        fun defaultContent(host: Activity): View = FrameLayout(host).apply {
            setBackgroundColor(SCRIM)
            addView(
                ProgressBar(host),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }

        const val SCRIM = 0xCC000000.toInt()
    }
}

private class AndroidSurface(host: Activity, private val content: (Activity) -> View) : OverlaySurface {
    private val hostRef = WeakReference(host)
    private var app: Application? = host.application
    private var root: FrameLayout? = null
    var onHostGone: () -> Unit = {}
    private var callback: OnBackPressedCallback? = null

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(activity: Activity) = gone(activity)

        override fun onActivityDestroyed(activity: Activity) = gone(activity)

        private fun gone(activity: Activity) {
            if (activity === hostRef.get()) onHostGone()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    override fun add() {
        val host = checkNotNull(hostRef.get()) { "host gone" }
        val parent = host.findViewById<ViewGroup>(android.R.id.content)
        val frame = FrameLayout(host).apply {
            isClickable = true
            isFocusable = true
            addView(content(host), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Fallback for non-ComponentActivity hosts: swallow Back key while focused.
            setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        }
        parent.addView(frame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root = frame
        frame.requestFocus()
        app?.registerActivityLifecycleCallbacks(lifecycle)
    }

    override fun setBackBlocked(blocked: Boolean) {
        val host = hostRef.get() as? ComponentActivity ?: return
        if (blocked) {
            val cb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            }
            callback = cb
            host.onBackPressedDispatcher.addCallback(cb)
        } else {
            callback?.remove()
            callback = null
        }
    }

    override fun remove() {
        app?.unregisterActivityLifecycleCallbacks(lifecycle)
        app = null
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
    }

}
