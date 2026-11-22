package com.example.adlib.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.ads.mobile.sdk.banner.AdView

/** Debug screen measuring the real thread of every SDK callback (see [ThreadProbe]). */
class MainActivity : ComponentActivity() {
    private lateinit var probe: AdProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = AdProbe(applicationContext)
        val adView = AdView(this)
        setContent {
            MaterialTheme {
                val entries by ThreadProbe.entries.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Row {
                        Button({ probe.initialize(lifecycleScope) }) { Text(stringResource(R.string.probe_init)) }
                        Button({ ThreadProbe.clear() }) { Text(stringResource(R.string.probe_clear)) }
                    }
                    Row {
                        Button({ probe.loadInterstitial() }) { Text(stringResource(R.string.probe_load_inter)) }
                        Button({ probe.loadRewarded() }) { Text(stringResource(R.string.probe_load_rewarded)) }
                        Button({ probe.loadAppOpen() }) { Text(stringResource(R.string.probe_load_appopen)) }
                    }
                    Row {
                        Button({ probe.loadNative() }) { Text(stringResource(R.string.probe_load_native)) }
                        Button({ probe.loadBanner(this@MainActivity, adView) }) { Text(stringResource(R.string.probe_load_banner)) }
                    }
                    Row {
                        Button({ probe.showInterstitial(this@MainActivity) }) { Text(stringResource(R.string.probe_show_inter)) }
                        Button({ probe.showRewarded(this@MainActivity) }) { Text(stringResource(R.string.probe_show_rewarded)) }
                        Button({ probe.showAppOpen(this@MainActivity) }) { Text(stringResource(R.string.probe_show_appopen)) }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    AndroidView(factory = { adView })
                }
            }
        }
    }
}
