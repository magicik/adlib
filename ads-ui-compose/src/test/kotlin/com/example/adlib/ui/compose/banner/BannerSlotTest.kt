package com.example.adlib.ui.compose.banner

import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.BannerHandle
import com.example.adlib.testing.FakeAdHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerSlotTest {
    private val pending = ArrayDeque<(BannerHandle?) -> Unit>()
    private val released = mutableListOf<BannerHandle>()
    private val slot = BannerSlot({ _, cb -> pending.addLast(cb) }, { released += it })
    private fun ad() = FakeAdHandle("b", AdFormat.BANNER, 0L)

    @Test
    fun `loads once, Shown with an ad, Gone without and no retry`() {
        slot.load(320)
        slot.load(320)
        assertEquals(1, pending.size)
        assertEquals(BannerState.Loading, slot.state.value)
        val ad = ad()
        pending.removeFirst()(ad)
        assertEquals(BannerState.Shown(ad), slot.state.value)

        val failed = BannerSlot({ _, cb -> pending.addLast(cb) }, { released += it })
        failed.load(320)
        pending.removeFirst()(null)
        failed.load(400)
        assertEquals(BannerState.Gone, failed.state.value)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `dispose or Premium releases the shown ad, and an ad arriving after dispose is released`() {
        slot.load(320)
        val shown = ad()
        pending.removeFirst()(shown)
        slot.dispose()
        assertEquals(listOf<BannerHandle>(shown), released)
        assertEquals(BannerState.Gone, slot.state.value)

        val other = BannerSlot({ _, cb -> pending.addLast(cb) }, { released += it })
        other.load(320)
        other.dispose()
        val late = ad()
        pending.removeFirst()(late)
        assertTrue(released.contains(late))

        val premium = BannerSlot({ _, cb -> pending.addLast(cb) }, { released += it })
        premium.load(320)
        val p = ad()
        pending.removeFirst()(p)
        premium.onPremium()
        assertTrue(released.contains(p))
        assertEquals(BannerState.Gone, premium.state.value)
    }
}
