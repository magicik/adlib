package com.example.adlib.testing

import com.example.adlib.core.fullscreen.ForegroundSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Manually driven [ForegroundSource]; starts in the background (process just created). */
class FakeForegroundSource(initial: Boolean = false) : ForegroundSource {
    private val state = MutableStateFlow(initial)
    override val isForeground: StateFlow<Boolean> = state.asStateFlow()

    fun toForeground() {
        state.value = true
    }

    fun toBackground() {
        state.value = false
    }
}
