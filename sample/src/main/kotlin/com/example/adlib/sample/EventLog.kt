package com.example.adlib.sample

import android.util.Log
import com.example.adlib.core.events.AdsEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Last [MAX] library events (also logged under tag [TAG]). */
object EventLog {
    const val TAG = "AdLibSample"
    private const val MAX = 50

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(event: AdsEvent) = add(describe(event))

    fun add(line: String) {
        Log.i(TAG, line)
        _lines.update { (listOf(line) + it).take(MAX) }
    }

    private fun describe(event: AdsEvent): String = when (event) {
        is AdsEvent.Paid -> "${event.placement}: Paid ${event.paid.valueMicros / MICROS} ${event.paid.currencyCode}"
        else -> "${event.placement}: $event"
    }

    private const val MICROS = 1_000_000.0
}
