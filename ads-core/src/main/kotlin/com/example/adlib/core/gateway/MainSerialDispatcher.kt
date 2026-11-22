package com.example.adlib.core.gateway

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The single serial "main" dispatcher every gateway callback is delivered on (design v3 6.7).
 *
 * Unlike `Dispatchers.Main.immediate` it NEVER runs a block inline, even when already on the main
 * thread: every block goes through the delegate's queue, so callbacks keep the order in which
 * they were posted and cannot re-enter the code that posted them.
 */
class MainSerialDispatcher(
    private val delegate: CoroutineDispatcher = Dispatchers.Main,
) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, block)
    }

    /** Queues [block] behind everything already posted. Safe to call from any thread. */
    fun post(block: () -> Unit) {
        dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { block() })
    }
}
