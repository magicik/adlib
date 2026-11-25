package com.example.adlib.core.placement

import com.example.adlib.core.gateway.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlacementRegistryTest {
    private val a = Placement("a", AdFormat.INTERSTITIAL, "shared")

    private val invalid = mapOf(
        "duplicate name" to listOf(a, Placement("a", AdFormat.NATIVE)),
        "poolKey with other format" to listOf(a, Placement("b", AdFormat.NATIVE, "shared")),
    )

    @Test
    fun `invalid lists throw in strict mode`() {
        invalid.forEach { (label, list) ->
            assertThrows(label, IllegalArgumentException::class.java) { PlacementRegistry(list, strict = true) }
        }
    }

    @Test
    fun `invalid lists warn and skip the offender in release mode`() {
        invalid.forEach { (label, list) ->
            val warnings = mutableListOf<String>()
            val registry = PlacementRegistry(list, strict = false) { warnings += it }
            assertEquals(label, 1, warnings.size)
            assertEquals(label, listOf(a), registry.all)
        }
    }

    @Test
    fun `shared poolKey with same format is allowed`() {
        val b = Placement("b", AdFormat.INTERSTITIAL, "shared")
        val registry = PlacementRegistry(listOf(a, b), strict = true)
        assertEquals(b, registry["b"])
        assertNull(registry["zzz"])
    }
}
