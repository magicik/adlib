package com.example.adlib.core.state

import com.example.adlib.core.config.ConfigParser
import com.example.adlib.core.config.ConfigWarningSink
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventBus
import com.example.adlib.core.placement.Placement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-supplied remote config: raw JSON of a placement, or null. Read on every lookup (R8). */
fun interface ConfigProvider {
    fun json(placementName: String): String?
}

/**
 * Process-level state read by every load and show: Premium, kill switch, config, consent.
 * [canRequestAds] is supplied as a function so this class does not depend on the consent wiring.
 */
internal class AdsState(
    initialPremium: Boolean,
    private val configProvider: ConfigProvider,
    private val events: AdsEventBus,
    private val canRequestAds: () -> Boolean,
) {
    private val _isPremium = MutableStateFlow(initialPremium)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    @Volatile
    private var killSwitch = false

    fun setPremium(value: Boolean) {
        _isPremium.value = value
    }

    fun setKillSwitch(value: Boolean) {
        killSwitch = value
    }

    /** Fresh config for [placement]; parser warnings go to the event listener. */
    fun config(placement: Placement): PlacementConfig {
        val sink = ConfigWarningSink { events.emit(AdsEvent.ConfigWarning(placement.name, it)) }
        return ConfigParser(sink).parse(configProvider.json(placement.name), placement.default)
    }

    /** The one gate used at load AND at show (v3 6.6). */
    fun isAdAllowed(placement: Placement): Boolean = canServe() && config(placement).enable

    /** The same gate with the config the caller already read for this load (avoids a second parse). */
    fun isAdAllowed(config: PlacementConfig): Boolean = canServe() && config.enable

    private fun canServe(): Boolean = !_isPremium.value && !killSwitch && canRequestAds()
}
