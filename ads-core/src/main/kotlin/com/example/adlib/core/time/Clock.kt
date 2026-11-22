package com.example.adlib.core.time

/** Time source so expiry logic can be tested with a fake clock (no Thread.sleep). */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
