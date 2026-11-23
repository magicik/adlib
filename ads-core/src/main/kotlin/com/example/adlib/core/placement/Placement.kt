package com.example.adlib.core.placement

import com.example.adlib.core.config.PlacementDefault
import com.example.adlib.core.gateway.AdFormat

/** An ad slot the app declares. Placements sharing a [poolKey] share one pool and must share a format. */
data class Placement(
    val name: String,
    val format: AdFormat,
    val poolKey: String = name,
    val default: PlacementDefault? = null,
)

/**
 * Immutable lookup of registered placements. Invalid input (duplicate name, a poolKey used by two
 * formats) throws when [strict] (debug); otherwise it is reported through [onWarning] and the
 * offending placement is skipped.
 */
class PlacementRegistry(placements: List<Placement>, strict: Boolean, onWarning: (String) -> Unit = {}) {
    private val byName: Map<String, Placement>

    init {
        val accepted = LinkedHashMap<String, Placement>()
        val poolFormats = HashMap<String, AdFormat>()
        for (p in placements) {
            val problem = when {
                accepted.containsKey(p.name) -> "duplicate placement name '${p.name}'"
                poolFormats[p.poolKey].let { it != null && it != p.format } ->
                    "poolKey '${p.poolKey}' used by formats ${poolFormats[p.poolKey]} and ${p.format}"
                else -> null
            }
            if (problem != null) {
                if (strict) throw IllegalArgumentException(problem)
                onWarning(problem)
                continue
            }
            accepted[p.name] = p
            poolFormats[p.poolKey] = p.format
        }
        byName = accepted
    }

    val all: List<Placement> get() = byName.values.toList()

    operator fun get(name: String): Placement? = byName[name]
}
