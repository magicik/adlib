package com.example.adlib.core.events

import com.example.adlib.core.gateway.MainSerialDispatcher

/** Delivers events to the app listener on [dispatcher], never inline, and survives a throwing listener. */
internal class AdsEventBus(
    private val dispatcher: MainSerialDispatcher,
    @Volatile var listener: AdsEventListener? = null,
) {
    fun emit(event: AdsEvent) {
        dispatcher.post {
            try {
                listener?.onEvent(event)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // A faulty app listener must never break ad flow.
            }
        }
    }
}
