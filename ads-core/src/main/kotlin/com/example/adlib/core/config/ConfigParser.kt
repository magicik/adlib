package com.example.adlib.core.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Receives non-fatal config problems. Kept free of android.util.Log so the parser runs on the JVM. */
internal fun interface ConfigWarningSink {
    fun warn(message: String)

    companion object {
        val None = ConfigWarningSink { }
    }
}

/**
 * Tolerant parser for the new placement schema (design v3, section 3):
 * - empty / null / unparseable / non-object JSON -> the whole [PlacementDefault];
 * - a missing or wrongly typed field -> that field of the default (nested objects field by field,
 *   `normal` as a whole list); no default -> [PlacementConfig.DISABLED];
 * - values are coerced like org.json (`"true"`, `"15000"`); unknown keys are ignored;
 * - legacy JSON (`list_ads` without `tiers`/`version`) is not read and reported to [warnings].
 */
internal class ConfigParser(private val warnings: ConfigWarningSink = ConfigWarningSink.None) {

    fun parse(json: String?, default: PlacementDefault?): PlacementConfig {
        val base = default ?: PlacementConfig.DISABLED
        val obj = json?.toJsonObject() ?: return base
        if (obj.has(LIST_ADS) && !obj.has(TIERS) && !obj.has(VERSION)) {
            warnings.warn(LEGACY_SCHEMA_IGNORED)
            return base
        }
        return PlacementConfig(
            enable = obj.bool("enable") ?: base.enable,
            totalTimeoutMs = obj.long("total_timeout_ms") ?: base.totalTimeoutMs,
            tiers = obj.optJSONObject(TIERS)?.let { tiers(it, base.tiers) } ?: base.tiers,
            refresh = obj.optJSONObject("refresh")?.let { refresh(it, base.refresh) } ?: base.refresh,
            goneInTestMode = obj.bool("gone_in_test_mode") ?: base.goneInTestMode,
        )
    }

    private fun tiers(obj: JSONObject, base: AdTiers) = AdTiers(
        highFloor = obj.optJSONObject("high_floor")?.let {
            HighFloorTier(
                adUnit = it.string("adunit") ?: base.highFloor.adUnit,
                enable = it.bool("enable") ?: base.highFloor.enable,
            )
        } ?: base.highFloor,
        normal = obj.optJSONArray("normal")?.let(::normalTiers) ?: base.normal,
    )

    private fun normalTiers(array: JSONArray): List<AdUnitTier> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
            AdUnitTier(
                adUnit = it.string("adunit").orEmpty(),
                timeoutMs = it.long("timeout_ms") ?: AdUnitTier.DEFAULT_TIMEOUT_MS,
                enable = it.bool("enable") ?: true,
            )
        }

    private fun refresh(obj: JSONObject, base: RefreshConfig) = RefreshConfig(
        seconds = obj.int("seconds") ?: base.seconds,
        maxCount = obj.int("max_count") ?: base.maxCount,
    )

    private fun String.toJsonObject(): JSONObject? = try {
        JSONObject(this)
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.string(key: String): String? = opt(key) as? String

    private fun JSONObject.bool(key: String): Boolean? = when (val value = opt(key)) {
        is Boolean -> value
        is String -> when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> null
        }
        else -> null
    }

    private fun JSONObject.long(key: String): Long? = when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        else -> null
    }

    private fun JSONObject.int(key: String): Int? =
        long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    companion object {
        const val LEGACY_SCHEMA_IGNORED = "legacy schema ignored (list_ads without tiers/version)"
        private const val LIST_ADS = "list_ads"
        private const val TIERS = "tiers"
        private const val VERSION = "version"
    }
}
