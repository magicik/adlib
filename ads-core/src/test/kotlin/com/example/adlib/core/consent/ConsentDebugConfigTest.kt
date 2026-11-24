package com.example.adlib.core.consent

import com.google.android.ump.ConsentDebugSettings.DebugGeography
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ConsentDebugConfigTest {
    @Test
    fun defaultIsInactiveAndGeographyMapsToUmp() {
        assertFalse(ConsentDebugConfig().isActive)
        assertTrue(ConsentDebugConfig(ConsentDebugGeography.EEA).isActive)
        assertEquals(DebugGeography.DEBUG_GEOGRAPHY_EEA, ConsentDebugGeography.EEA.umpValue())
        assertEquals(DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA, ConsentDebugGeography.NOT_EEA.umpValue())
        assertEquals(DebugGeography.DEBUG_GEOGRAPHY_DISABLED, ConsentDebugGeography.DISABLED.umpValue())
    }
}
