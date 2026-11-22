package com.example.adlib.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigParserTest {

    private val appDefault = PlacementConfig(
        totalTimeoutMs = 20_000,
        tiers = AdTiers(
            highFloor = HighFloorTier(adUnit = "hf", enable = true),
            normal = listOf(AdUnitTier("default-unit")),
        ),
        refresh = RefreshConfig(seconds = 30, maxCount = 2),
        goneInTestMode = true,
    )

    private class Case(
        val name: String,
        val json: String?,
        val expected: PlacementConfig,
        val default: PlacementDefault? = null,
        val warns: Boolean = false,
    )

    @Test
    fun `parse applies the v3 default, coercion and tolerance rules`() {
        val cases = listOf(
            Case("empty", "", appDefault, appDefault),
            Case("null", null, appDefault, appDefault),
            Case("empty object", "{}", appDefault, appDefault),
            Case("broken json", "{", appDefault, appDefault),
            Case("number string", "15000", appDefault, appDefault),
            Case("boolean string", "true", appDefault, appDefault),
            Case("old tier type", "MAX", appDefault, appDefault),
            Case(
                "legacy list_ads is ignored with a warning",
                """{"enable":false,"list_ads":[{"enable_ad":true,"adunit":"old"}]}""",
                appDefault, appDefault, warns = true,
            ),
            Case(
                "missing fields come from the default one by one",
                """{"total_timeout_ms":25000,"tiers":{"high_floor":{"enable":false}}}""",
                appDefault.copy(
                    totalTimeoutMs = 25_000,
                    tiers = appDefault.tiers.copy(highFloor = HighFloorTier(adUnit = "hf", enable = false)),
                ),
                appDefault,
            ),
            Case(
                "string values are coerced like org.json",
                """{"enable":"FALSE","total_timeout_ms":"30000","gone_in_test_mode":"false",
                   "refresh":{"seconds":"60","max_count":3.0},
                   "tiers":{"normal":[{"adunit":"u","timeout_ms":"5000","enable":"true"}]}}""",
                PlacementConfig(
                    enable = false,
                    totalTimeoutMs = 30_000,
                    tiers = appDefault.tiers.copy(normal = listOf(AdUnitTier("u", timeoutMs = 5_000))),
                    refresh = RefreshConfig(seconds = 60, maxCount = 3),
                    goneInTestMode = false,
                ),
                appDefault,
            ),
            Case(
                "wrong type per field falls back to that field",
                """{"enable":"yes","total_timeout_ms":true,"tiers":"x","refresh":[1],"gone_in_test_mode":null}""",
                appDefault, appDefault,
            ),
            Case(
                "wrong type in nested fields falls back to that field",
                """{"tiers":{"high_floor":{"enable":"maybe","adunit":5},"normal":{"adunit":"u"}},
                   "refresh":{"seconds":"soon","max_count":99999999999}}""",
                appDefault, appDefault,
            ),
            Case(
                "unknown keys are ignored",
                """{"foo":1,"tiers":{"bar":[],"normal":[{"adunit":"u","type":"max"}]}}""",
                appDefault.copy(tiers = appDefault.tiers.copy(normal = listOf(AdUnitTier("u")))),
                appDefault,
            ),
            Case("no default and no json is off", null, PlacementConfig.DISABLED),
            Case("no default and empty object is off", "{}", PlacementConfig.DISABLED),
            Case(
                "no default but json turns it on uses global values",
                """{"enable":true,"tiers":{"normal":[{"adunit":"u"}]}}""",
                PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("u")))),
            ),
        )

        for (case in cases) {
            val warnings = mutableListOf<String>()
            val actual = ConfigParser { warnings += it }.parse(case.json, case.default)
            assertEquals(case.name, case.expected, actual)
            assertEquals(case.name, if (case.warns) listOf(ConfigParser.LEGACY_SCHEMA_IGNORED) else emptyList<String>(), warnings)
        }
    }

    @Test
    fun `enabledAds drops blank, disabled and non-object normal tiers, and all of them when the placement is off`() {
        val json = """{"tiers":{"normal":[{"adunit":""},{"adunit":"off","enable":false},"x",{"adunit":"ok"}]}}"""

        val on = ConfigParser().parse(json, appDefault)
        val off = ConfigParser().parse(json.replaceFirst("{", """{"enable":false,"""), appDefault)

        assertEquals(listOf(AdUnitTier("ok")), on.enabledAds)
        assertEquals(emptyList<AdUnitTier>(), off.enabledAds)
    }
}
