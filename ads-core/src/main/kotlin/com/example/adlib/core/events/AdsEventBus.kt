package com.example.adlib.core.events

import com.example.adlib.core.gateway.MainSerialDispatcher

/**
 * Delivers events to the app listener on [dispatcher], never inline, and survives a throwing listener.
 * Lib features can [observe] the same stream (e.g. App Open skips the return after an ad click).
 */
internal class AdsEventBus(
    private val dispatcher: MainSerialDispatcher,
    @Volatile var listener: AdsEventListener? = null,
) {
    private val observers = mutableListOf<(AdsEvent) -> Unit>()

    /** Call on [dispatcher]; [observer] runs there, before the app listener. */
    fun observe(observer: (AdsEvent) -> Unit) {
        observers += observer
    }

    fun emit(event: AdsEvent) {
        dispatcher.post {
            observers.forEach { it(event) }
            try {
                listener?.onEvent(event)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // A faulty app listener must never break ad flow.
            }
        }
    }
}
