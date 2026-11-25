package com.example.adlib.ui.compose.nativead

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.adlib.core.placement.Placement
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd as SdkNativeAd

// Built-in layouts (v3 8). Every block has a fixed size shared with its placeholder, so a slot does
// not jump when the ad arrives (R37). The top-right corner is left free for the AdChoices icon the SDK
// adds. Large needs a width of at least 214dp (MediaView >= 120dp high at 16:9).

/** Large card: header, 16:9 media, two lines of body, full-width call to action. */
@Composable
fun NativeAdLarge(placement: Placement, adIndex: Int, modifier: Modifier = Modifier) =
    NativeAd(placement, adIndex, loading = { NativeAdLargePlaceholder() }, modifier = modifier) { NativeAdLargeContent(it) }

/** Small (compact) row: icon, headline, one line of body, call to action. */
@Composable
fun NativeAdSmall(placement: Placement, adIndex: Int, modifier: Modifier = Modifier) =
    NativeAd(placement, adIndex, loading = { NativeAdSmallPlaceholder() }, modifier = modifier) { NativeAdSmallContent(it) }

@Composable
fun NativeAdLargeContent(ad: SdkNativeAd) {
    Column(Modifier.fillMaxWidth().padding(Padding)) {
        Row(Modifier.fillMaxWidth().height(LargeHeader), verticalAlignment = Alignment.CenterVertically) {
            Icon(ad, LargeIcon)
            Spacer(Modifier.width(Gap))
            Column(Modifier.weight(1f)) {
                TitleRow(ad)
                ad.advertiser?.let { text ->
                    NativeAdAdvertiserView {
                        Text(
                            text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.width(AdChoicesSpace))
        }
        Spacer(Modifier.height(Gap))
        NativeAdMediaView(Modifier.fillMaxWidth().aspectRatio(MEDIA_RATIO))
        Spacer(Modifier.height(Gap))
        Box(Modifier.fillMaxWidth().height(LargeBody)) {
            ad.body?.let { text ->
                NativeAdBodyView { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(Gap))
        CallToAction(ad, Modifier.fillMaxWidth().height(CtaHeight))
    }
}

@Composable
fun NativeAdLargePlaceholder() {
    Column(Modifier.fillMaxWidth().padding(Padding)) {
        Row(Modifier.fillMaxWidth().height(LargeHeader), verticalAlignment = Alignment.CenterVertically) {
            Block(Modifier.size(LargeIcon))
            Spacer(Modifier.width(Gap))
            Block(Modifier.weight(1f).height(TitleLine))
            Spacer(Modifier.width(AdChoicesSpace))
        }
        Spacer(Modifier.height(Gap))
        Block(Modifier.fillMaxWidth().aspectRatio(MEDIA_RATIO))
        Spacer(Modifier.height(Gap))
        Block(Modifier.fillMaxWidth().height(LargeBody))
        Spacer(Modifier.height(Gap))
        Block(Modifier.fillMaxWidth().height(CtaHeight))
    }
}

@Composable
fun NativeAdSmallContent(ad: SdkNativeAd) {
    Row(Modifier.fillMaxWidth().height(SmallHeight).padding(Padding), verticalAlignment = Alignment.CenterVertically) {
        Icon(ad, SmallIcon)
        Spacer(Modifier.width(Gap))
        Column(Modifier.weight(1f)) {
            TitleRow(ad)
            ad.body?.let { text ->
                NativeAdBodyView { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.width(Gap))
        CallToAction(ad, Modifier.width(SmallCtaWidth).height(SmallCtaHeight))
    }
}

@Composable
fun NativeAdSmallPlaceholder() {
    Row(Modifier.fillMaxWidth().height(SmallHeight).padding(Padding), verticalAlignment = Alignment.CenterVertically) {
        Block(Modifier.size(SmallIcon))
        Spacer(Modifier.width(Gap))
        Block(Modifier.weight(1f).height(TitleLine))
        Spacer(Modifier.width(Gap))
        Block(Modifier.width(SmallCtaWidth).height(SmallCtaHeight))
    }
}

/** "Ad" label first, then the headline: the attribution stays visible inside the NativeAdView. */
@Composable
private fun TitleRow(ad: SdkNativeAd) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NativeAdAttribution()
        Spacer(Modifier.width(Gap / 2))
        ad.headline?.let { text ->
            NativeAdHeadlineView {
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun Icon(ad: SdkNativeAd, size: Dp) {
    val drawable: Drawable? = ad.icon?.drawable
    if (drawable == null) {
        Spacer(Modifier.size(size))
        return
    }
    NativeAdIconView(Modifier.size(size)) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { it.setImageDrawable(drawable) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CallToAction(ad: SdkNativeAd, modifier: Modifier) {
    val text = ad.callToAction
    if (text == null) {
        Spacer(modifier)
        return
    }
    NativeAdCallToActionView(modifier) { NativeAdButton(text, Modifier.fillMaxSize()) }
}

@Composable
private fun Block(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small))
}

private const val MEDIA_RATIO = 16f / 9f
private val Padding = 12.dp
private val Gap = 8.dp
private val AdChoicesSpace = 16.dp
private val TitleLine = 20.dp
private val LargeHeader = 48.dp
private val LargeIcon = 40.dp
private val LargeBody = 40.dp
private val CtaHeight = 44.dp
private val SmallHeight = 80.dp
private val SmallIcon = 48.dp
private val SmallCtaWidth = 96.dp
private val SmallCtaHeight = 40.dp
