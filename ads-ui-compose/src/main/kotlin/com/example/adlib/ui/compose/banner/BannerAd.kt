package com.example.adlib.ui.compose.banner

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.adlib.core.banner.BannerAdSource
import com.example.adlib.core.placement.Placement

/** The lib's banner source. `Ads.install` provides it (T11); tests and previews may provide a fake. */
val LocalBannerAdSource = staticCompositionLocalOf<BannerAdSource> {
    error("No BannerAdSource: call Ads.install or provide LocalBannerAdSource")
}

/**
 * Anchored adaptive banner sized to the available width, loaded directly for this View (no pool).
 * Nothing is drawn, and no space reserved, while loading or after a failure / Premium / disabled (R3).
 * The ad is destroyed when the composable leaves the composition. Use an Activity context.
 */
@Composable
fun BannerAd(placement: Placement, modifier: Modifier = Modifier) {
    val source = LocalBannerAdSource.current
    val context = LocalContext.current
    val slot = remember(source, placement) { BannerSlot({ w, cb -> source.request(placement, w, context, cb) }, source::release) }
    DisposableEffect(slot) { onDispose { slot.dispose() } }
    val premium by source.isPremium.collectAsState()
    LaunchedEffect(slot, premium) { if (premium) slot.onPremium() }
    val state by slot.state.collectAsState()
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value.toInt()
        LaunchedEffect(slot, widthDp) { if (!premium && widthDp > 0) slot.load(widthDp) }
        (state as? BannerState.Shown)?.handle?.view?.let { view ->
            AndroidView(factory = { view })
        }
    }
}
