package com.example.adlib.sample

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Screen(val titleRes: Int) {
    HOME(R.string.screen_home),
    NATIVE(R.string.screen_native),
    BANNER(R.string.screen_banner),
    INTERSTITIAL(R.string.screen_interstitial),
    REWARDED(R.string.screen_rewarded),
    APP_OPEN(R.string.screen_app_open),
    PROBE(R.string.screen_probe),
}

/** Event log on top of every screen, the current demo screen below it. */
@Composable
fun SampleRoot(activity: Activity) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        EventLogPanel()
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                Screen.HOME -> HomeScreen(activity, onOpen = { screen = it })
                Screen.NATIVE -> NativeScreen()
                Screen.BANNER -> BannerScreen()
                Screen.INTERSTITIAL -> InterstitialScreen(activity, onNavigated = { screen = Screen.HOME })
                Screen.REWARDED -> RewardedScreen(activity)
                Screen.APP_OPEN -> AppOpenScreen(activity)
                Screen.PROBE -> ProbeScreen(activity)
            }
        }
    }
}

@Composable
private fun EventLogPanel() {
    val lines by EventLog.lines.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.event_log_title), style = MaterialTheme.typography.labelMedium)
        LazyColumn {
            items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
