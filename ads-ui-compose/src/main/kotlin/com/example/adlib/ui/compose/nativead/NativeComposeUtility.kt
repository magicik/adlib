/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Source: https://github.com/googleads/gma-next-gen-sdk-android-examples/blob/dc398eead450763712798cd588a5aecbe9e4c3a2/kotlin/NextGenExample/app/src/main/java/com/example/nextgenexample/native/NativeComposeUtility.kt
 * (commit dc398eead450763712798cd588a5aecbe9e4c3a2, copied 2026-11-23). Third-party notice: /NOTICE.
 *
 * Modified by AdLib (Apache-2.0 section 4(b)):
 * - package renamed from com.example.nextgenexample.native;
 * - NativeAdAttribution: the default text is the translatable string resource adlib_native_ad_attribution;
 * - NativeAdButton: no click handler (a Compose Button consumes the touch the SDK needs for the CTA
 *   click); it is drawn as a button-styled Box + Text, like the older compose_utils sample.
 */

package com.example.adlib.ui.compose.nativead

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.adlib.ui.compose.R
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/**
 * A CompositionLocal that can provide a `NativeAd` to ad attributes such as `NativeAdMediaView`.
 */
internal val LocalNativeAd = staticCompositionLocalOf<NativeAd?> { null }

/**
 * A CompositionLocal that can provide a `NativeAdView` to ad attributes such as `NativeHeadline`.
 */
internal val LocalNativeAdView = staticCompositionLocalOf<NativeAdView?> { null }

/**
 * A CompositionLocal that can provide a registration function for the MediaView. This is used to
 * cache the MediaView so it can be passed to registerNativeAd.
 *
 * **Mechanism:**
 * 1. `NativeAdView` (parent) provides a callback `(MediaView) -> Unit` via this CompositionLocal.
 * 2. `NativeAdMediaView` (child) retrieves this callback and invokes it with its created
 *    `MediaView` instance.
 * 3. `NativeAdView` captures this instance in a `MutableState`.
 * 4. `NativeAdView`'s `DisposableEffect` uses the captured `MediaView` to call
 *    `registerNativeAd(nativeAd, mediaView)`.
 *
 * This allows the parent to access the child's View instance for the required SDK registration
 * call.
 */
internal val LocalMediaViewRegister = staticCompositionLocalOf<(MediaView?) -> Unit> { {} }

/**
 * This is the Compose wrapper for a NativeAdView.
 *
 * @param nativeAd The `NativeAd` object containing the ad assets to be displayed in this view.
 * @param modifier The modifier to apply to the native ad.
 * @param content A composable function that defines the rest of the native ad view's elements.
 */
@Composable
fun NativeAdView(
    nativeAd: NativeAd,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val nativeAdViewRef = remember { mutableStateOf<NativeAdView?>(null) }
    val mediaViewRef = remember { mutableStateOf<MediaView?>(null) }

    AndroidView(
        factory = { context ->
            val composeView =
                ComposeView(context).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                }
            NativeAdView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                addView(composeView)
                nativeAdViewRef.value = this
            }
        },
        modifier = modifier,
        update = { view ->
            // If the child is a ComposeView, set the content.
            // This is safe because we added it in the factory.
            val composeView = view.getChildAt(0) as? ComposeView
            composeView?.setContent {
                // By providing the NativeAd and NativeAdView objects, the descendant composables can
                // access them to register themselves with the NativeAdView and populate the view with
                // the correct ad asset.
                val registerMediaView: (MediaView?) -> Unit = remember { { mv -> mediaViewRef.value = mv } }
                CompositionLocalProvider(
                    LocalNativeAdView provides view,
                    LocalNativeAd provides nativeAd,
                    LocalMediaViewRegister provides registerMediaView,
                ) {
                    content()
                }
            }
        },
    )
    val currentNativeAd by rememberUpdatedState(nativeAd)
    val currentNativeAdView = nativeAdViewRef.value
    val currentMediaView = mediaViewRef.value

    DisposableEffect(currentNativeAd, currentNativeAdView, currentMediaView) {
        // Register the ad whenever the NativeAd, NativeAdView, or MediaView changes.
        // This ensures that even if one of these dependencies updates asynchronously,
        // the registration is re-attempted.
        currentNativeAdView?.register(currentNativeAd, currentMediaView)

        onDispose {
            // No specific cleanup is needed here. The SDK handles unregistering when the NativeAdView
            // is detached from the window.
        }
    }
}

/**
 * The ComposeWrapper container for an advertiserView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdAdvertiserView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.advertiserView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper container for a bodyView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdBodyView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.bodyView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper container for a callToActionView inside a NativeAdView. This composable must
 * be invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdCallToActionView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.callToActionView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper for a adChoicesView inside a NativeAdView. This composable must be invoked
 * from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 */
@Composable
fun NativeAdChoicesView(modifier: Modifier = Modifier) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context ->
            AdChoicesView(context).apply {
                minimumWidth = 15
                minimumHeight = 15
            }
        },
        modifier = modifier,
        update = { view -> nativeAdView.adChoicesView = view },
    )
}

/**
 * The ComposeWrapper container for a headlineView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdHeadlineView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.headlineView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper container for a iconView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdIconView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.iconView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper for a mediaView inside a NativeAdView. This composable must be invoked from
 * within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param scaleType The ImageView.ScaleType to apply to the image/media within the MediaView.
 */
@Composable
fun NativeAdMediaView(modifier: Modifier = Modifier, scaleType: ImageView.ScaleType? = null) {
    val registerMediaView = LocalMediaViewRegister.current
    AndroidView(
        factory = { context -> MediaView(context) },
        update = { view ->
            registerMediaView(view)
            scaleType?.let { type -> view.imageScaleType = type }
        },
        modifier = modifier,
    )

    DisposableEffect(Unit) { onDispose { registerMediaView(null) } }
}

/**
 * The ComposeWrapper container for a priceView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdPriceView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.priceView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper container for a starRatingView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdStarRatingView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.starRatingView = view
            view.setContent(content)
        },
    )
}

/**
 * The ComposeWrapper container for a storeView inside a NativeAdView. This composable must be
 * invoked from within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param content A composable function that defines the content of this native asset.
 */
@Composable
fun NativeAdStoreView(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nativeAdView = LocalNativeAdView.current ?: throw IllegalStateException("NativeAdView null")
    AndroidView(
        factory = { context -> ComposeView(context) },
        modifier = modifier,
        update = { view ->
            nativeAdView.storeView = view
            view.setContent(content)
        },
    )
}

/**
 * The composable for a ad attribution inside a NativeAdView. This composable must be invoked from
 * within a `NativeAdView`.
 *
 * @param modifier modify the native ad view element.
 * @param text The string identifying this view as an advertisement.
 * @param shape The shape of the attribution.
 * @param containerColor The background color of the attribution.
 * @param contentColor The text color of the attribution.
 * @param padding The padding around the attribution text.
 */
@Composable
fun NativeAdAttribution(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.adlib_native_ad_attribution),
    shape: Shape = MaterialTheme.shapes.extraSmall,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    padding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
) {
    Box(modifier = modifier.background(color = containerColor, shape = shape).padding(padding)) {
        Text(text = text, color = contentColor, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A composable button for native ads. It has no click handler on purpose: put it inside
 * `NativeAdCallToActionView` and the SDK handles the click.
 *
 * @param text The text to display on the button.
 * @param modifier The modifier to apply to the button.
 * @param shape The shape of the button.
 * @param colors The colors to use for the button.
 * @param contentPadding The padding to apply to the content of the button.
 */
@Composable
fun NativeAdButton(
    text: String,
    modifier: Modifier = Modifier,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    Box(
        modifier = modifier.background(color = colors.containerColor, shape = shape).padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = colors.contentColor, style = MaterialTheme.typography.labelLarge)
    }
}

private fun NativeAdView.register(nativeAd: NativeAd, mediaView: MediaView?) {
    if (mediaView != null) {
        // Post the registration to ensure the MediaView has been measured (layout pass complete).
        // This prevents the "120x120" validator check from failing if the view is initially 0x0.
        mediaView.post { this.registerNativeAd(nativeAd, mediaView) }
    } else {
        this.registerNativeAd(nativeAd, null)
    }
}
