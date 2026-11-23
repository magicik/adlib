package com.example.adlib.core.appopen

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlaySessionTest {
    private class FakeSurface(val failAdd: Boolean = false, val failRemove: Boolean = false) : OverlaySurface {
        var added = 0
        var removed = 0
        var blocked = false

        override fun add() {
            if (failAdd) error("add")
            added++
        }

        override fun remove() {
            removed++
            if (failRemove) error("remove")
        }

        override fun setBackBlocked(blocked: Boolean) {
            this.blocked = blocked
        }
    }

    @Test
    fun backBlockedOnlyWhileVisible() {
        val s = FakeSurface()
        val shown = OverlaySession(s).start()
        assertEquals(true, s.blocked)
        shown.hide()
        shown.hide()
        assertEquals(false, s.blocked)
        assertEquals(1, s.removed)
    }

    @Test
    fun hostGoneHidesAndFreesBack() {
        val s = FakeSurface()
        val session = OverlaySession(s)
        session.start()
        session.onHostGone()
        assertEquals(false, s.blocked)
        assertEquals(1, s.removed)
    }

    @Test
    fun addThrowingLeavesNothingBlocked() {
        val s = FakeSurface(failAdd = true)
        OverlaySession(s).start().hide()
        assertEquals(false, s.blocked)
    }

    @Test
    fun removeThrowingStillFreesBack() {
        val s = FakeSurface(failRemove = true)
        val shown = OverlaySession(s).start()
        runCatching { shown.hide() }
        assertEquals(false, s.blocked)
    }
}
