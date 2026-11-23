package com.example.adlib.core.testmode

/**
 * Persisted evidence for test-mode detection. [totalMicrosByCurrency] is kept per currency, never summed
 * across currencies. [hasPaidEvent] is true once any paid event arrived, even with value 0.
 */
data class RevenueSnapshot(
    val totalMicrosByCurrency: Map<String, Long> = emptyMap(),
    val hasPaidEvent: Boolean = false,
    val nativeTestAdSeen: Boolean = false,
)

/**
 * App-provided persistence (e.g. SharedPreferences). [load] is called once, at install, off the hot path;
 * [save] is called on the serial dispatcher and must not block (use `apply()` / a background write).
 */
interface RevenueStore {
    fun load(): RevenueSnapshot

    fun save(snapshot: RevenueSnapshot)
}
