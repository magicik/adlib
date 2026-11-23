package com.example.adlib.core.appopen

/** What an overlay needs from the platform; Android-free so the lifecycle logic is unit-testable. */
internal interface OverlaySurface {
    fun add()

    fun remove()

    fun setBackBlocked(blocked: Boolean)
}

/**
 * One overlay's lifecycle. Back is blocked exactly while the overlay is visible; [hide] (idempotent) is the
 * single cleanup for every ending: the lib's hide, host stop/destroy, errors.
 */
internal class OverlaySession(private val surface: OverlaySurface) : AppOpenOverlay.Shown {
    private var visible = false

    fun start(): AppOpenOverlay.Shown {
        try {
            surface.add()
            visible = true
            surface.setBackBlocked(true)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A broken overlay must never stop the ad flow: end up with nothing visible and Back free.
            hide()
        }
        return this
    }

    /** Host Activity stopped or destroyed. */
    fun onHostGone() = hide()

    override fun hide() {
        if (!visible) return
        visible = false
        try {
            surface.setBackBlocked(false)
        } finally {
            surface.remove()
        }
    }
}
