package com.example.adlib.sample

import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records the real thread each SDK callback arrives on (logcat tag AdThreadProbe + on-screen list). */
object ThreadProbe {
    const val TAG = "AdThreadProbe"

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun record(callback: String) {
        val thread = Thread.currentThread()
        val isMain = Looper.myLooper() == Looper.getMainLooper()
        val line = "$callback -> ${thread.name} (main=$isMain)"
        Log.i(TAG, line)
        _entries.value = _entries.value + line
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
