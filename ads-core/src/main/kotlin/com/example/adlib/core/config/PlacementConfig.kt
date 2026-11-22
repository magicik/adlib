package com.example.adlib.core.config

/**
 * Config of one placement, new schema only (design v3, section 3). Field defaults are the global
 * fallback values; [enable] defaults to `true` so an app declaring a [PlacementDefault] only has to
 * list its ad units.
 */
data class PlacementConfig(
    val enable: Boolean = true,
    /** Budget for the normal tiers only; high floor has its own cap (design 6.3). */
    val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    val tiers: AdTiers = AdTiers(),
    val refresh: RefreshConfig = RefreshConfig(),
    val goneInTestMode: Boolean = false,
) {
    /** Normal tiers that can actually be loaded: none if the placement is off, else non-blank and enabled ones. */
    val enabledAds: List<AdUnitTier>
        get() = if (enable) tiers.normal.filter { it.enable && it.adUnit.isNotBlank() } else emptyList()

    companion object {
        const val DEFAULT_TOTAL_TIMEOUT_MS = 15_000L

        /** Used when a placement declares no default: off, with the global fallback values. */
        val DISABLED = PlacementConfig(enable = false)
    }
}

/**
 * Default an app declares when registering a placement. Every field missing from the remote JSON is
 * taken from it; `null` means the placement is off unless the JSON turns it on.
 */
typealias PlacementDefault = PlacementConfig

data class AdTiers(
    val highFloor: HighFloorTier = HighFloorTier(),
    /** Tried in list order. */
    val normal: List<AdUnitTier> = emptyList(),
)

data class HighFloorTier(
    val adUnit: String = "",
    val enable: Boolean = false,
)

data class AdUnitTier(
    val adUnit: String,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val enable: Boolean = true,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

/** Native only: reload a shown ad every [seconds], at most [maxCount] times; 0 = off. */
data class RefreshConfig(
    val seconds: Int = 0,
    val maxCount: Int = 0,
)
