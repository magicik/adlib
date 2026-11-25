package com.example.adlib.core.fullscreen.interstitial

import android.content.Context
import android.content.SharedPreferences

/** Default [CapStore]: the cap counters in a private SharedPreferences file. [save] uses `apply()` (async disk write). */
internal class SharedPreferencesCapStore(private val prefs: SharedPreferences) : CapStore {
    override fun load(): CapSnapshot = CapSnapshot(
        dayBucket = prefs.getLong(KEY_DAY, -1L),
        dayCount = prefs.getInt(KEY_COUNT, 0).coerceAtLeast(0),
        lastShownAt = if (prefs.contains(KEY_LAST)) prefs.getLong(KEY_LAST, 0L) else null,
        attempts = prefs.getInt(KEY_ATTEMPTS, 0).coerceAtLeast(0),
    )

    override fun save(snapshot: CapSnapshot) {
        prefs.edit().apply {
            putLong(KEY_DAY, snapshot.dayBucket)
            putInt(KEY_COUNT, snapshot.dayCount)
            putInt(KEY_ATTEMPTS, snapshot.attempts)
            snapshot.lastShownAt?.let { putLong(KEY_LAST, it) } ?: remove(KEY_LAST)
        }.apply()
    }

    companion object {
        private const val FILE = "adlib_interstitial_caps"
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"
        private const val KEY_LAST = "last"
        private const val KEY_ATTEMPTS = "attempts"

        fun of(context: Context) = SharedPreferencesCapStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
