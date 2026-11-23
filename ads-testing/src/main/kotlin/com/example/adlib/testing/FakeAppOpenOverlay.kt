package com.example.adlib.testing

import android.app.Activity
import com.example.adlib.core.appopen.AppOpenOverlay

/** Records overlay requests; [visible] = overlays shown and not hidden yet. */
class FakeAppOpenOverlay : AppOpenOverlay {
    val hosts = mutableListOf<Activity>()
    var visible = 0
        private set

    /** When set, [show] throws this. */
    var showThrows: RuntimeException? = null

    override fun show(host: Activity): AppOpenOverlay.Shown {
        showThrows?.let { throw it }
        hosts += host
        visible++
        var hidden = false
        return AppOpenOverlay.Shown {
            if (!hidden) {
                hidden = true
                visible--
            }
        }
    }
}
