package com.example.adlib.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.example.adlib.core.Ads

/** Hosts the demo screens (see [SampleRoot]). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.consent.gather(this)
        setContent { MaterialTheme { SampleRoot(this) } }
    }
}
