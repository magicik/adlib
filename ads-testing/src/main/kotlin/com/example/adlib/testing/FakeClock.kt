package com.example.adlib.testing

import com.example.adlib.core.time.Clock

/** Manually advanced clock. Pair with virtual time (runTest), never Thread.sleep. */
class FakeClock(private var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(ms: Long) {
        nowMs += ms
    }

    fun set(ms: Long) {
        nowMs = ms
    }
}
