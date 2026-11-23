package com.example.adlib.ui.compose.nativead

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adlib.core.Ads
import com.example.adlib.core.nativead.NativeAdSource
import com.example.adlib.core.placement.Placement
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd as SdkNativeAd

/** The lib's native source. Defaults to `Ads.native` (needs `Ads.install`); tests and previews may provide a fake. */
val LocalNativeAdSource = staticCompositionLocalOf<NativeAdSource> {
    Ads.native
}

/** Holds a screen's native ads; scoped to the current ViewModelStoreOwner (NavBackStackEntry), M5. */
internal class NativeAdsViewModel(source: NativeAdSource) : ViewModel() {
    val holder = NativeSlotHolder(source, viewModelScope)

    override fun onCleared() = holder.clear()
}

/**
 * One native ad slot (v3 4, 8). [adIndex] is the ad's ordinal on this screen (not the list position):
 * the same (placement, adIndex) keeps its ad across recomposition, scrolling (within the cap) and
 * rotation. [loading] is drawn while the ad loads and should have the size of [content] (R37); on
 * no ad / disabled / Premium / test-mode rules nothing is drawn (R3). [content] runs inside the SDK
 * NativeAdView: build it from the `NativeAd*View` asset wrappers and `NativeAdAttribution`, and add
 * no click handlers.
 */
@Composable
fun NativeAd(
    placement: Placement,
    adIndex: Int,
    loading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (ad: SdkNativeAd) -> Unit,
) {
    val source = LocalNativeAdSource.current
    val holder = viewModel(key = VIEW_MODEL_KEY) { NativeAdsViewModel(source) }.holder
    val slot = remember(holder, placement, adIndex) { holder.slot(placement, adIndex) }
    DisposableEffect(slot) {
        holder.onVisible(slot)
        onDispose { holder.onHidden(slot) }
    }
    val state by slot.state.collectAsState()
    when (val current = state) {
        NativeSlotState.Idle, NativeSlotState.Loading -> Box(modifier) { loading() }
        is NativeSlotState.Shown -> (current.ad.sdkObject as? SdkNativeAd)?.let { ad ->
            NativeAdView(ad, modifier) { content(ad) }
        }
        NativeSlotState.Gone -> Unit
    }
}

private const val VIEW_MODEL_KEY = "com.example.adlib.ui.compose.NativeAds"
