package com.example.adlib.sample

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.adlib.core.Ads
import com.example.adlib.ui.compose.banner.BannerAd
import com.example.adlib.ui.compose.nativead.NativeAdLarge
import com.example.adlib.ui.compose.nativead.NativeAdSmall

private val screenPadding = Modifier.fillMaxSize().padding(12.dp)

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun HomeScreen(activity: Activity, onOpen: (Screen) -> Unit) {
    val premium by Ads.isPremium.collectAsStateWithLifecycle()
    var killSwitch by remember { mutableStateOf(false) }
    val privacyRequired by Ads.consent.privacyOptionsRequired.collectAsStateWithLifecycle()
    Column(screenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Screen.entries.filter { it != Screen.HOME }.forEach { s ->
            Button({ onOpen(s) }, Modifier.fillMaxWidth()) { Text(stringResource(s.titleRes)) }
        }
        LabeledSwitch(stringResource(R.string.toggle_premium), premium) { Ads.setPremium(it) }
        LabeledSwitch(stringResource(R.string.toggle_kill_switch), killSwitch) {
            killSwitch = it
            Ads.setKillSwitch(it)
        }
        if (privacyRequired) {
            Button({ Ads.consent.showPrivacyOptions(activity) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.privacy_options))
            }
        }
    }
}

private const val NATIVE_SLOTS = 14

@Composable
fun NativeScreen() {
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        items((0 until NATIVE_SLOTS).toList(), span = { if (it % 2 == 0) GridItemSpan(2) else GridItemSpan(1) }) { i ->
            if (i % 2 == 0) {
                NativeAdLarge(SamplePlacements.nativeLarge, adIndex = i, modifier = Modifier.padding(4.dp))
            } else {
                NativeAdSmall(SamplePlacements.nativeSmall, adIndex = i, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

@Composable
fun BannerScreen() {
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.banner_hint), Modifier.weight(1f).padding(12.dp))
        BannerAd(SamplePlacements.banner, Modifier.fillMaxWidth())
    }
}

@Composable
fun InterstitialScreen(activity: Activity, onNavigated: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    Column(screenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ Ads.interstitial.preload(SamplePlacements.interstitial) }) { Text(stringResource(R.string.preload)) }
        Button(
            {
                running = true
                Ads.interstitial.showThen(activity, SamplePlacements.interstitial) {
                    EventLog.add("showThen action ran: navigate")
                    running = false
                    onNavigated()
                }
            },
            enabled = !running,
        ) { Text(stringResource(R.string.next)) }
    }
}

@Composable
fun RewardedScreen(activity: Activity) {
    val placement = SamplePlacements.rewarded
    val allowed by remember { Ads.rewarded.isAllowed(placement) }.collectAsStateWithLifecycle(false)
    val available by remember { Ads.rewarded.isAvailable(placement) }.collectAsStateWithLifecycle(false)
    var status by remember { mutableStateOf(R.string.reward_idle) }
    var unlocked by remember { mutableStateOf(false) }
    Column(screenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ Ads.rewarded.preload(placement) }) { Text(stringResource(R.string.preload)) }
        Text(stringResource(R.string.reward_available, available.toString()))
        Button(
            {
                status = R.string.reward_waiting
                Ads.rewarded.request(
                    activity,
                    placement,
                    onEarned = { unlocked = true },
                    onClosed = { status = R.string.reward_closed },
                    onUnavailable = { status = R.string.reward_unavailable },
                )
            },
            enabled = allowed,
        ) { Text(stringResource(R.string.reward_unlock)) }
        Text(stringResource(status))
        if (unlocked) Text(stringResource(R.string.reward_earned), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun AppOpenScreen(activity: Activity) {
    Column(screenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ Ads.appOpen.pauseForExternalFlow() }) { Text(stringResource(R.string.app_open_pause)) }
        Button({
            Ads.appOpen.pauseForExternalFlow()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "AdLib sample")
            activity.startActivity(Intent.createChooser(send, null))
        }) { Text(stringResource(R.string.app_open_external)) }
        Text(stringResource(R.string.app_open_hint))
    }
}
