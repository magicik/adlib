package com.example.adlib.ui.compose.nativead

import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.placement.Placement
import com.example.adlib.testing.FakeAdHandle
import com.example.adlib.testing.FakeNativeAdSource
import com.example.adlib.ui.compose.nativead.NativeSlotState.Gone
import com.example.adlib.ui.compose.nativead.NativeSlotState.Idle
import com.example.adlib.ui.compose.nativead.NativeSlotState.Loading
import com.example.adlib.ui.compose.nativead.NativeSlotState.Shown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSlotHolderTest {
    private val placement = Placement("feed", AdFormat.NATIVE)
    private val source = FakeNativeAdSource()

    private fun TestScope.holder() = NativeSlotHolder(source, backgroundScope).also { runCurrent() }

    private fun NativeSlotHolder.show(index: Int) = slot(placement, index).also { onVisible(it) }

    @Test
    fun `a visible slot loads once - Shown with an ad, Gone without, and Gone does not retry`() = runTest {
        val h = holder()
        val withAd = h.show(0)
        val noAd = h.show(1)
        assertEquals(Loading, withAd.state.value)
        val ad = source.answerNext()
        source.answerNext(null)

        assertEquals(Shown(ad!!), withAd.state.value)
        assertEquals(Gone, noAd.state.value)
        h.onHidden(noAd)
        h.onVisible(noAd)
        assertEquals(Gone, noAd.state.value)
        assertEquals(2, source.requestCount)
    }

    @Test
    fun `rotation - every slot hidden then shown again - keeps the ads and asks for none`() = runTest {
        val h = holder()
        val slots = (0 until 8).map { h.show(it) }
        repeat(8) { source.answerNext() }

        slots.forEach(h::onHidden)
        slots.forEach(h::onVisible)

        assertEquals(8, source.requestCount)
        assertTrue(source.released.isEmpty())
        assertTrue(slots.all { it.state.value is Shown })
    }

    @Test
    fun `scrolling past the cap releases the least recently visible ad and scrolling back takes a new one`() = runTest {
        val h = holder()
        // 12 items scrolled one by one with 2 visible at a time.
        val slots = (0 until 12).map { h.slot(placement, it) }
        val ads = mutableListOf<FakeAdHandle>()
        slots.forEachIndexed { i, slot ->
            h.onVisible(slot)
            ads += source.answerNext() as FakeAdHandle
            if (i >= 1) h.onHidden(slots[i - 1])
        }

        // Cap max(8, 2 + 1) = 8: items 0..3 were released oldest first, destroyed, back to Idle.
        assertEquals(ads.take(4), source.released)
        assertTrue(ads.take(4).all { it.destroyed })
        assertTrue(slots.take(4).all { it.state.value == Idle })
        assertTrue(slots.drop(4).all { it.state.value is Shown })

        h.onVisible(slots[0])
        assertEquals(Loading, slots[0].state.value)
        assertEquals(13, source.requestCount)
        assertEquals("item 4 is now the least recently visible", ads[4], source.released.last())
    }

    @Test
    fun `the cap grows to visible + 1 when more than 8 slots are visible`() = runTest {
        val h = holder()
        val slots = (0 until 10).map { h.show(it) }
        repeat(10) { source.answerNext() }
        h.onHidden(slots[0])
        val eleventh = h.show(10)
        source.answerNext()

        // 10 visible -> cap 11: the hidden slot 0 keeps its ad too.
        assertTrue(source.released.isEmpty())
        assertTrue((slots + eleventh).all { it.state.value is Shown })
    }

    @Test
    fun `onCleared and Premium destroy every ad, and an answer arriving later is released`() = runTest {
        for (case in listOf("onCleared", "premium")) {
            val src = FakeNativeAdSource()
            val h = NativeSlotHolder(src, backgroundScope).also { runCurrent() }
            val shown = h.slot(placement, 0).also { h.onVisible(it) }
            val loading = h.slot(placement, 1).also { h.onVisible(it) }
            val ad = src.answerNext() as FakeAdHandle

            if (case == "onCleared") h.clear() else src.isPremium.value = true
            runCurrent()
            val late = src.answerNext() as FakeAdHandle

            assertEquals(case, listOf(ad, late), src.released)
            assertTrue(case, ad.destroyed && late.destroyed)
            assertEquals(case, Gone, shown.state.value)
            assertEquals(case, Gone, loading.state.value)
        }
    }
}
