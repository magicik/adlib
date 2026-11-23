package com.example.adlib.core.bootstrap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T2/T3 of design v3 section 5. Created at Ads.install (T0) on the main serial [scope].
 *
 * - SDK init starts ONCE, on the first time [canRequestAds] is true (already true at T0 from the
 *   previous session, or later after the form / privacy options / a late UMP answer).
 * - Remote config wait ([awaitConfig]) starts at T0, in parallel, capped at [configWaitMs] from T0;
 *   on timeout or error loads go on with the stored/default config.
 * - [awaitReadyToLoad] is what every load calls first: false immediately when ads cannot be requested
 *   (R11, never hangs); otherwise waits for init up to [initWaitMs] PER WAITER. Giving up does not
 *   cancel init; later loads proceed once it lands.
 */
class AdsBootstrap(
    private val canRequestAds: StateFlow<Boolean>,
    private val initializer: SdkInitializer,
    private val scope: CoroutineScope,
    awaitConfig: suspend () -> Unit = {},
    private val initWaitMs: Long = DEFAULT_INIT_WAIT_MS,
    configWaitMs: Long = DEFAULT_CONFIG_WAIT_MS,
) {
    private val initDone = CompletableDeferred<Unit>()
    private val initStarted = AtomicBoolean(false)

    private val _sdkInitialized = MutableStateFlow(false)
    val sdkInitialized: StateFlow<Boolean> = _sdkInitialized.asStateFlow()

    private val configDone = scope.async {
        try {
            withTimeoutOrNull(configWaitMs) { awaitConfig() }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null // stored/default config is used
        }
        Unit
    }

    init {
        scope.launch {
            canRequestAds.first { it }
            startInit()
        }
    }

    private fun startInit() {
        if (!initStarted.compareAndSet(false, true)) return
        initializer.initialize {
            scope.launch {
                _sdkInitialized.value = true
                initDone.complete(Unit)
            }
        }
    }

    /** True when a load may be sent now. Bounded by [initWaitMs] plus what is left of the config cap. */
    suspend fun awaitReadyToLoad(): Boolean {
        if (!canRequestAds.value) return false
        val initialized = withTimeoutOrNull(initWaitMs) { initDone.await() } != null
        configDone.await()
        return initialized && canRequestAds.value
    }

    companion object {
        const val DEFAULT_INIT_WAIT_MS = 4_000L
        const val DEFAULT_CONFIG_WAIT_MS = 8_000L
    }
}
