package com.example.adlib.core.consent

enum class ConsentDebugGeography { DISABLED, EEA, NOT_EEA }

/**
 * UMP debug settings for testing the consent form outside the EEA. Default = none (production unchanged).
 * [testDeviceHashedIds] are the ids UMP prints in logcat ("Use new ConsentDebugSettings.Builder().addTestDeviceHashedId").
 * UMP applies the geography only on those devices (or emulators).
 */
data class ConsentDebugConfig(
    val geography: ConsentDebugGeography = ConsentDebugGeography.DISABLED,
    val testDeviceHashedIds: List<String> = emptyList(),
) {
    internal val isActive: Boolean get() = geography != ConsentDebugGeography.DISABLED || testDeviceHashedIds.isNotEmpty()
}
