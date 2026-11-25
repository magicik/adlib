package com.example.adlib.core

import android.app.Activity
import com.example.adlib.core.config.AdTiers
import com.example.adlib.core.config.AdUnitTier
import com.example.adlib.core.config.PlacementConfig
import com.example.adlib.core.events.AdsEvent
import com.example.adlib.core.events.AdsEventListener
import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.MainSerialDispatcher
import com.example.adlib.core.placement.Placement
import com.example.adlib.testing.FakeActivityTracker
import com.example.adlib.testing.FakeAdGateway
import com.example.adlib.testing.FakeAppOpenOverlay
import com.example.adlib.testing.FakeClock
import com.example.adlib.testing.FakeConsentSource
import com.example.adlib.testing.FakeForegroundSource
import com.example.adlib.testing.FakeSdkInitializer
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AdsRuntimeTest {
    private val inter = Placement(
        "inter",
        AdFormat.INTERSTITIAL,
        default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("n1", 10_000)))),
    )

    @Test
    fun `install to consent to init to preload to showThen with all fakes`() = runTest {
        val serial = MainSerialDispatcher(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(SupervisorJob() + serial)
        val consentSource = FakeConsentSource(canRequestAds = false)
        val initializer = FakeSdkInitializer()
        val gateway = FakeAdGateway(scope, serial, FakeClock())
        val options = AdsOptions(listOf(inter))
        val runtime = AdsRuntime(
            options, AdsRuntime.validate(options), scope, serial, consentSource, initializer,
            gateway, FakeActivityTracker(), FakeForegroundSource(), FakeClock(),
        )
        val activity = mockk<Activity>(relaxed = true)
        runCurrent()
        assertEquals("no consent: SDK not started", 0, initializer.calls)

        runtime.consent.gather(activity)
        runCurrent()
        consentSource.canRequestAds = true
        consentSource.answerUpdate()
        runCurrent()
        assertEquals(1, initializer.calls)

        runtime.interstitial.preload(inter)
        runCurrent()
        assertTrue("no load before the SDK finished init", gateway.loadRequests.isEmpty())
        initializer.complete()
        runCurrent()
        assertEquals(1, gateway.loadRequests.size)

        val ran = AtomicInteger()
        runtime.interstitial.showThen(activity, inter) { ran.incrementAndGet() }
        runCurrent()
        assertEquals("ad shown", 1, gateway.shown.size)
        gateway.dismiss(gateway.handles[0])
        runCurrent()
        assertEquals("action ran once", 1, ran.get())
        scope.cancel()
    }

    @Test
    fun `bad placements throw at install in debug and are reported in release`() = runTest {
        val dup = listOf(inter, inter)
        try {
            AdsRuntime.validate(AdsOptions(dup, debug = true))
            fail("debug must throw")
        } catch (_: IllegalArgumentException) {
        }

        val serial = MainSerialDispatcher(StandardTestDispatcher(testScheduler))
        val scope = CoroutineScope(SupervisorJob() + serial)
        val events = mutableListOf<AdsEvent>()
        val options = AdsOptions(dup, listener = AdsEventListener { events += it })
        val validated = AdsRuntime.validate(options)
        assertEquals(1, validated.warnings.size)
        AdsRuntime(
            options, validated, scope, serial, FakeConsentSource(), FakeSdkInitializer(),
            FakeAdGateway(scope, serial, FakeClock()), FakeActivityTracker(), FakeForegroundSource(), FakeClock(),
        )
        runCurrent()
        assertTrue(events.any { it is AdsEvent.ConfigWarning && it.message == validated.warnings[0] })
        scope.cancel()
    }

    @Test
    fun `install attaches App Open resume unless opted out or Premium, and an explicit attach adds no second watcher`() = runTest {
        val appOpen = Placement(
            "app_open",
            AdFormat.APP_OPEN,
            default = PlacementConfig(tiers = AdTiers(normal = listOf(AdUnitTier("a1", 10_000)))),
        )
        fun coversAfterReturn(autoAttach: Boolean = true, premium: Boolean = false, explicitAttach: Boolean = false): Int {
            val serial = MainSerialDispatcher(StandardTestDispatcher(testScheduler))
            val scope = CoroutineScope(SupervisorJob() + serial)
            val overlay = FakeAppOpenOverlay()
            val options = AdsOptions(
                listOf(appOpen),
                appOpenOverlay = overlay,
                autoAttachResume = autoAttach,
                initialPremium = premium,
            )
            val foreground = FakeForegroundSource()
            val tracker = FakeActivityTracker()
            val runtime = AdsRuntime(
                options, AdsRuntime.validate(options), scope, serial, FakeConsentSource(canRequestAds = true),
                FakeSdkInitializer(), FakeAdGateway(scope, serial, FakeClock()), tracker, foreground, FakeClock(),
            )
            val activity = mockk<Activity>(relaxed = true)
            if (explicitAttach) runtime.appOpen?.attachResume()
            runCurrent()
            foreground.toForeground()
            tracker.resumeAppActivity(activity)
            runCurrent()
            tracker.pauseAppActivity()
            foreground.toBackground()
            runCurrent()
            foreground.toForeground()
            runCurrent()
            tracker.resumeAppActivity(activity)
            runCurrent()
            scope.cancel()
            return overlay.hosts.size
        }
        assertEquals("auto attach", 1, coversAfterReturn())
        assertEquals("explicit attach after install", 1, coversAfterReturn(explicitAttach = true))
        assertEquals("opted out", 0, coversAfterReturn(autoAttach = false))
        assertEquals("premium", 0, coversAfterReturn(premium = true))
    }
}
