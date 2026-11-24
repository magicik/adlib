package com.example.adlib.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.adlib.core.Ads
import androidx.compose.material3.MaterialTheme

/** Hosts the demo screens (see [SampleRoot]). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ads.consent.gather(this)
        setContent { MaterialTheme { SampleRoot(this) } }
    }
}
