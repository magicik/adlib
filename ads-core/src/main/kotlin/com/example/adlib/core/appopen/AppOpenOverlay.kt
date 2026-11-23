package com.example.adlib.core.appopen

import android.app.Activity

/**
 * The full-screen scrim + progress covering the host while the first return of the process loads its
 * App Open ad (v3 7, Q-E). Drawn by ads-ui (T8), which also blocks Back while it is shown (Q-F).
 * Called on the main thread.
 */
fun interface AppOpenOverlay {
    /**
     * Covers [host]. The lib always calls [Shown.hide] when the wait ends (ad about to show, no ad,
     * timeout or the `overlayMaxMs` cap, background, Premium); the overlay must also remove itself
     * when [host] stops or is destroyed, and must not keep [host] after that.
     */
    fun show(host: Activity): Shown

    fun interface Shown {
        /** Removes the overlay; calling it again is a no-op. */
        fun hide()
    }
}
