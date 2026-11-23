package com.example.adlib.ui.compose.banner

import com.example.adlib.core.gateway.BannerHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal sealed interface BannerState {
    /** Nothing is drawn and no space is reserved (R3). */
    data object Loading : BannerState

    data class Shown(val handle: BannerHandle) : BannerState

    data object Gone : BannerState
}

/** One banner View's state: load once per (width), destroy on dispose / Premium, Gone after a failure. Main thread. */
internal class BannerSlot(
    private val request: (widthDp: Int, onResult: (BannerHandle?) -> Unit) -> Unit,
    private val release: (BannerHandle) -> Unit,
) {
    private val _state = MutableStateFlow<BannerState>(BannerState.Loading)
    val state: StateFlow<BannerState> = _state

    private var disposed = false
    private var loadStarted = false

    fun load(widthDp: Int) {
        if (disposed || loadStarted) return
        loadStarted = true
        request(widthDp) { handle -> onResult(handle) }
    }

    private fun onResult(handle: BannerHandle?) {
        when {
            handle == null -> _state.value = BannerState.Gone
            disposed || _state.value == BannerState.Gone -> release(handle)
            else -> _state.value = BannerState.Shown(handle)
        }
    }

    fun onPremium() {
        (_state.value as? BannerState.Shown)?.let { release(it.handle) }
        _state.value = BannerState.Gone
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        (_state.value as? BannerState.Shown)?.let { release(it.handle) }
        _state.value = BannerState.Gone
    }
}
