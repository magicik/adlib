package com.example.adlib.ui.compose.nativead

import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.nativead.NativeAdSource
import com.example.adlib.core.placement.Placement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** R41. Idle and Loading draw the placeholder (same size, R37); Gone takes no space (R3). */
internal sealed interface NativeSlotState {
    data object Idle : NativeSlotState

    data object Loading : NativeSlotState

    data class Shown(val ad: NativeHandle) : NativeSlotState

    data object Gone : NativeSlotState
}

/** One native position of a screen, key (placement, adIndex) (v3 4: inserting or filtering items keeps the key). */
internal class NativeSlot(val placement: Placement) {
    internal val mutableState = MutableStateFlow<NativeSlotState>(NativeSlotState.Idle)
    val state: StateFlow<NativeSlotState> = mutableState
    internal var visibleCount = 0
    internal var lastVisible = 0L

    /** Identity of the request this slot waits for; any other answer is released. */
    internal var pending: Any? = null

    internal val holds: Boolean
        get() = state.value == NativeSlotState.Loading || state.value is NativeSlotState.Shown
}

/**
 * The native ads of ONE screen (v3 8; lives in a ViewModel of the NavBackStackEntry, M5), so a
 * rotation keeps them. Main thread only. A slot loads when it becomes visible and is Idle; Gone stays
 * Gone for the screen's life (no request loop on no-fill). When a slot starts loading and more than
 * max([minCap], visible + 1) slots hold an ad (or wait for one), the least recently visible ones are
 * released (F3): ad destroyed, back to Idle, so scrolling back takes a new ad from the pool.
 * Every ad leaves through [release] or a stale answer, never both.
 */
internal class NativeSlotHolder(
    private val source: NativeAdSource,
    scope: CoroutineScope,
    private val minCap: Int = DEFAULT_MIN_CAP,
) {
    private val slots = HashMap<Pair<String, Int>, NativeSlot>()
    private var tick = 0L

    init {
        scope.launch { source.isPremium.collect { if (it) slots.values.forEach { slot -> reset(slot, NativeSlotState.Gone) } } }
    }

    fun slot(placement: Placement, adIndex: Int): NativeSlot =
        slots.getOrPut(placement.name to adIndex) { NativeSlot(placement) }

    fun onVisible(slot: NativeSlot) {
        slot.visibleCount++
        slot.lastVisible = ++tick
        if (slot.state.value != NativeSlotState.Idle) return
        val request = Any()
        slot.pending = request
        slot.mutableState.value = NativeSlotState.Loading
        trim()
        source.request(slot.placement) { ad -> answer(slot, request, ad) }
    }

    fun onHidden(slot: NativeSlot) {
        slot.visibleCount--
        slot.lastVisible = ++tick
    }

    /** ViewModel.onCleared: destroys every ad; answers still on the way are released when they come. */
    fun clear() = slots.values.forEach { reset(it, NativeSlotState.Gone) }

    private fun answer(slot: NativeSlot, request: Any, ad: NativeHandle?) {
        if (slot.pending !== request) {
            ad?.let(source::release)
            return
        }
        slot.pending = null
        slot.mutableState.value = if (ad != null) NativeSlotState.Shown(ad) else NativeSlotState.Gone
    }

    private fun trim() {
        val cap = maxOf(minCap, slots.values.count { it.visibleCount > 0 } + 1)
        val holding = slots.values.filter { it.holds }
        val excess = holding.size - cap
        if (excess <= 0) return
        holding.filter { it.visibleCount == 0 }
            .sortedBy { it.lastVisible }
            .take(excess)
            .forEach { reset(it, NativeSlotState.Idle) }
    }

    private fun reset(slot: NativeSlot, to: NativeSlotState) {
        (slot.state.value as? NativeSlotState.Shown)?.let { source.release(it.ad) }
        slot.pending = null
        slot.mutableState.value = to
    }

    companion object {
        /** v3 0: 8 native slots per screen. */
        const val DEFAULT_MIN_CAP = 8
    }
}
