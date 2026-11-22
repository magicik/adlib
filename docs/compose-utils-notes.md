# Ghi chu compose_utils (native ad trong Compose)

Nguon da doc (nhanh `main`, doc ngay 2026-11-23, qua raw.githubusercontent.com / api.github.com):
- CU (GMA SDK cu): `googleads/googleads-mobile-android-examples` / `kotlin/advanced/JetpackComposeDemo/app/src/main/java/com/google/android/gms/example/jetpackcomposedemo/formats/compose_utils/`
  - Gom 3 file: `AdManagerBannerAd.kt` (2007 B), `BannerAd.kt` (1949 B), `NativeAdView.kt` (12843 B). Chi doc day du `NativeAdView.kt` (lien quan native). Hai file banner KHONG doc.
- NG (Next-Gen, uu tien): `googleads/gma-next-gen-sdk-android-examples` / `kotlin/NextGenExample/app/src/main/java/com/example/nextgenexample/native/`
  - `NativeComposeUtility.kt` (package `com.example.nextgenexample.native`) = ban Next-Gen cua NativeAdView.kt.
  - `NativeComposeFragment.kt` = vi du su dung (load, destroy, layout).
  - Dong so ben duoi la cua `NativeComposeUtility.kt` (NG) tru khi ghi "CU".

## 1) Danh sach ham/composable cong khai (NG)
Tat ca `@Composable fun`, trong 1 file. Tat ca (tru NativeAdView, NativeAdAttribution, NativeAdButton) phai goi ben trong `NativeAdView` (neu khong: `IllegalStateException("NativeAdView null")`, vd dong 158).

| Dong | Chu ky |
|---|---|
| 84 | `NativeAdView(nativeAd: NativeAd, modifier: Modifier = Modifier, content: @Composable () -> Unit)` |
| 157 | `NativeAdAdvertiserView(modifier, content)` |
| 177 | `NativeAdBodyView(modifier, content)` |
| 197 | `NativeAdCallToActionView(modifier, content)` |
| 216 | `NativeAdChoicesView(modifier)` (khong co content) |
| 238 | `NativeAdHeadlineView(modifier, content)` |
| 258 | `NativeAdIconView(modifier, content)` |
| 278 | `NativeAdMediaView(modifier, scaleType: ImageView.ScaleType? = null)` |
| 300 | `NativeAdPriceView(modifier, content)` |
| 320 | `NativeAdStarRatingView(modifier, content)` |
| 340 | `NativeAdStoreView(modifier, content)` |
| 364 | `NativeAdAttribution(modifier, text: String = "Ad", shape: Shape = MaterialTheme.shapes.extraSmall, containerColor = colorScheme.surfaceVariant, contentColor = colorScheme.onSurfaceVariant, padding: PaddingValues = PaddingValues(4.dp, 2.dp))` |
| 388 | `NativeAdButton(text: String, modifier, onClick: () -> Unit = {}, shape, colors: ButtonColors, contentPadding)` (NG: dung `Button` that) |

Noi bo: `LocalNativeAdView` (dong 57), `LocalNativeAd` (52, chi NG), `LocalMediaViewRegister` (74, chi NG), `private fun NativeAdView.register(nativeAd, mediaView?)` (407).
`modifier` la tham so thu 2 nen goi `NativeAdIconView(Modifier.padding(5.dp)) { ... }` duoc.

## 2) Cach dang ky asset, MediaView, vong doi

### Dang ky asset (HeadlineView, Body, Icon, CTA, Price, Store, Star, Advertiser)
Moi wrapper la `AndroidView(factory = { ComposeView(context) }, update = { view -> nativeAdView.<xxx>View = view; view.setContent(content) })` (vd headline dong 240-247). Tuc la moi asset = 1 `ComposeView` rieng nam trong cay View cua `NativeAdView`, gan vao property cua SDK `NativeAdView` (`headlineView`, `bodyView`, `iconView`, `callToActionView`, `priceView`, `starRatingView`, `storeView`, `advertiserView`). Noi dung Compose ben trong ve text/image tu `ad.headline` v.v. (xem `NativeComposeFragment.kt` 153-202; icon: `icon.drawable?.toBitmap()?.asImageBitmap()` dong 160).
- Asset chi duoc goi neu ad co gia tri (`ad.headline?.let {...}`): neu khong goi composable thi property view khong duoc gan.
- `update` chay moi lan recomposition cua AndroidView -> gan lai property + `setContent` lai moi lan (khong idempotent-chi phi, nhung ban goc lam vay).

### AdChoices (dong 216-228)
`AdChoicesView(context)` (NG: `com.google.android.libraries.ads.mobile.sdk.common.AdChoicesView`) voi `minimumWidth/Height = 15` (don vi px, khong phai dp), `update` gan `nativeAdView.adChoicesView = view`. Phai tu dat vi tri (thuong goc tren) bang modifier trong layout.

### MediaView (dong 278-290)
- NG: `AndroidView(factory = { MediaView(context) }, update = { registerMediaView(view); scaleType?.let { view.imageScaleType = it } })`, va `DisposableEffect(Unit) { onDispose { registerMediaView(null) } }`.
- Khac CU: CU gan truc tiep `nativeAdView.mediaView = view` va `view.setImageScaleType(type)` (CU dong 231-241). NG khong gan `mediaView` property ma truyen MediaView vao `registerNativeAd(nativeAd, mediaView)`.
- Co che (comment dong 59-73): `NativeAdView` cung cap callback `(MediaView?) -> Unit` qua `LocalMediaViewRegister`, con luu vao `mediaViewRef: MutableState<MediaView?>` (dong 90, 121).

### Vong doi: khi nao dang ky / huy
- NG (dong 132-146): `DisposableEffect(currentNativeAd, currentNativeAdView, currentMediaView) { currentNativeAdView?.register(ad, mediaView); onDispose { } }`. Chay lai khi 1 trong 3 doi (ke ca khi MediaView chuyen null -> view). `register` (dong 407-415): neu co mediaView thi `mediaView.post { registerNativeAd(nativeAd, mediaView) }` de MediaView da duoc do kich thuoc (tranh loi validator 120x120 khi 0x0); neu khong co thi `registerNativeAd(nativeAd, null)` ngay.
- `onDispose` RONG: comment noi SDK tu unregister khi `NativeAdView` bi detach khoi window (dong 143-144).
- CU (dong 98-99): `SideEffect { nativeAdViewRef.value?.setNativeAd(currentNativeAd) }` -> goi `setNativeAd` o MOI recomposition (khong co dieu kien).
- `NativeAd.destroy()` KHONG nam trong utility; do caller: `NativeComposeFragment.kt` 121-128 (`DisposableEffect(Unit){ onDispose { nativeAdIsDisposed = true; nativeAdState?.destroy(); nativeAdState = null } }`) va dong 102-107 (neu load xong khi da dispose thi `nativeAd.destroy()`). Tuc lib cua ta phai tu quan ly destroy (vd o holder/cache), utility chi dang ky.
- Recomposition: NG chi goi lai `registerNativeAd` khi khoa DisposableEffect doi (khong moi recomposition). Nhung `update` cua AndroidView van chay moi recomposition: gan lai view property va `setContent`, va `NativeAdView.update` cung `setContent` lai (dong 113-130). Khi `nativeAd` doi sang ad moi, `update` chay lai -> `LocalNativeAd` doi, DisposableEffect dang ky lai ad moi (ad cu: caller phai destroy).
- Rui ro tinh toan ma toi suy ra (khong thay trong nguon, can kiem chung khi chay that): `registerNativeAd` post nen co the chay sau khi composable da roi cay; NG khong huy `post` khi dispose.

## 3) NativeAdAttribution (nhan "Ad")
Khong phai View cua SDK. La `Box(modifier.background(containerColor, shape).padding(padding)) { Text(text, color, style = labelSmall) }` (NG dong 364-374; CU dong 315-326 dung mau Button + `Text` khong style). Khong dang ky vao `NativeAdView` property nao, chi la UI thuong, mac dinh "Ad", nen cho nhan nam trong cay `NativeAdView`. Khong co click. Chinh sach yeu cau nhan quang cao ro rang (vd "Ad"/"Advertisement") - can dat de nhin thay duoc (khong an, khong che).

## 4) Phu thuoc / doi ten Next-Gen vs cu

| Cu (GMA, CU) | Next-Gen (NG) |
|---|---|
| `com.google.android.gms.ads.nativead.NativeAd` | `com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd` |
| `...gms.ads.nativead.NativeAdView` | `...libraries.ads.mobile.sdk.nativead.NativeAdView` |
| `...gms.ads.nativead.MediaView` | `...libraries.ads.mobile.sdk.nativead.MediaView` |
| `...gms.ads.nativead.AdChoicesView` | `...libraries.ads.mobile.sdk.common.AdChoicesView` |
| `nativeAdView.setNativeAd(ad)` | `nativeAdView.registerNativeAd(ad, mediaView?)` |
| `nativeAdView.mediaView = view`; `view.setImageScaleType(t)` | truyen `mediaView` vao `registerNativeAd`; `view.imageScaleType = t` |
| `NativeAdButton`: Box+Text, KHONG co click | `NativeAdButton`: `Button(onClick)` that |
| `NativeAdAttribution` mau/ shape cua Button | `MaterialTheme.shapes.extraSmall`, `surfaceVariant/onSurfaceVariant`, `labelSmall` |

Cac ten property asset (`headlineView`, `bodyView`, `iconView`, `callToActionView`, `priceView`, `starRatingView`, `storeView`, `advertiserView`, `adChoicesView`) giu nguyen giua CU va NG theo nguon.

Phu thuoc (NG, `kotlin/NextGenExample/app/build.gradle.kts`): `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.7.0` (dong 47); `compose-bom:2025.04.00` (dong 53) voi `runtime-android`, `foundation-layout-android:1.7.8`, `ui`, `ui-graphics:1.7.8`, `material3`; `minSdk = 24` (dong 12); plugin `org.jetbrains.kotlin.plugin.compose` 2.1.0; NG exclude `play-services-ads` va `play-services-ads-lite` (dong 63-64) de tranh trung SDK cu. Khong thay phu thuoc Compose rieng nao khac.

Can sua khi chep vao module `ads-ui-compose`:
1. Doi `package` (NG la `com.example.nextgenexample.native`; `native` la ten package kha de gay nham, nen doi, vd `<pkg>.ui.compose`).
2. Giu import Next-Gen nhu bang tren; module phai co `ads-mobile-sdk` (api/compileOnly tuy thiet ke) + compose (ui, foundation, material3, runtime).
3. `NativeAdAttribution` va `NativeAdButton` hard-code `MaterialTheme` + `4.dp/2.dp` -> trong lib nen cho phep tham so/slot de khong phu thuoc Material3 neu khong muon (quyet dinh thiet ke cua ta; nguon khong noi).
4. `internal` Local* chi dung trong module -> OK neu giu chung 1 module.
5. Them destroy/quan ly vong doi `NativeAd` o lop ngoai (utility khong lam).
6. Xem xet bo `update { setContent }` lap lai (toi uu, khong bat buoc).

## 5) LICENSE header
Moi file bat dau bang header Apache-2.0 (dong 1-15): `Copyright 2026 Google LLC` (NG; CU la `2024`), "Licensed under the Apache License, Version 2.0 ... http://www.apache.org/licenses/LICENSE-2.0 ... WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND ...". Khi chep: giu nguyen header va nam ban quyen goc; ghi chu thay doi neu sua (Apache-2.0 muc 4: giu thong bao, ghi file da sua); them ban sao LICENSE/NOTICE trong repo thu vien. Toi khong doc file LICENSE o root cua repo mau (chi suy tu header) - nen xac nhan.

## 6) Rui ro chinh sach / ky thuat thay duoc
- Click handler: comment CU dong 332-336: "Jetpack Compose button implements a click handler which overrides the native ad click handler, causing issues"; CU `NativeAdButton` khong co click, dung `AdListener.onAdClicked`. NG `NativeAdButton` lai dung `Button(onClick = {})` (dong 396) va vi du dung no (fragment dong 198) - suy luan (chua chay thu): Button Compose co the nuot touch, lam SDK khong nhan click tren CTA. Nen dung Box+clickable-khong-co (nhu CU) hoac `Modifier` khong chan click; khong tu gan click len asset.
- Asset phai nam TRONG `NativeAdView`: moi asset composable bat buoc co `LocalNativeAdView` (neu khong crash). Khong dat asset ngoai khoi `NativeAdView`; nhan "Ad" cung nen o trong.
- Moi asset la `ComposeView` long trong ViewGroup: kiem tra `NativeAdView` that su chua cac view dang ky (SDK validator/Ad Inspector).
- MediaView toi thieu 120x120 dp (comment fragment dong 169-182); video can `aspectRatio` (fallback 16:9) + `heightIn(min = 120.dp)`. Dang ky dung `post` de tranh 0x0.
- Hardware acceleration video: KHONG thay noi trong nguon doc (manifest NG co `<application>` dong 5; toi chi grep `hardware|application`, khong thay `hardwareAccelerated`). Khong ket luan; can kiem tra tai lieu SDK/Manifest cua app.
- `AdChoicesView` min 15 (px) co the qua nho tren man hinh mat do cao; can dam bao nhin thay va khong bi che.
- Quan ly destroy do caller (xem muc 2); quen destroy = ro bo nho; destroy truoc khi hien thi lai = ad khong dung duoc.
- `NativeAdView` wrapper dung `MATCH_PARENT` va `ComposeView` long: dat `modifier` dung (khong de chieu cao khong gioi han trong `LazyColumn`).
- Khong doc: 2 file banner CU, README/LICENSE cua repo, tai lieu chinh thuc SDK; khong chay thu.
