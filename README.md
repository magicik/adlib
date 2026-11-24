# AdLib

Android ads library on top of the Google Mobile Ads Next-Gen SDK (`ads-mobile-sdk`) and UMP.
Design (source of truth): `docs/ads-lib-phase1-v3.txt`.

## Modules
- `ads-core` - Kotlin only (no View/Compose): config, pool, consent, events, full-screen ads.
- `ads-ui-compose` - Compose helpers (NativeAd, BannerAd).
- `ads-testing` - fakes for tests (not shipped in release apps).
- `sample` - demo app (`com.example.adlib.sample`, Google test app id).

`ads-ui-view` comes later. Root package: `com.example.adlib` (temporary).

## Build
```
export ANDROID_HOME=<sdk path>   # or local.properties (sdk.dir=..., git-ignored)
./scripts/check.sh               # assembleDebug + testDebugUnitTest, log in build/check.log
```
Style: no ktlint/detekt yet (not set up on purpose; add later).

## TODO publish (JitPack)
- Publishing blocks exist (`maven-publish`) with groupId `com.github.PLACEHOLDER`. TODO: replace with
  `com.github.<jitpack-user>` once the repo host/user is known. Nothing is published yet.
- If mediation is added later, exclude `play-services-ads` from dependencies.

## Usage
Install (once, `Application.onCreate`; no network, returns immediately):
```kotlin
Ads.install(this, AdsOptions(placements = listOf(...), debug = BuildConfig.DEBUG))
Ads.appOpen.attachResume()   // required for App Open on return to the app
```
Set `com.google.android.gms.ads.APPLICATION_ID` meta-data in the manifest.

- Consent: the app calls `Ads.consent.gather(activity)` (UMP). The SDK is initialised only once ads can be requested.
- Placements: `Placement(name, AdFormat, default = PlacementConfig(...))`. Remote JSON (new schema only, see
  `docs/ads-lib-phase1-v3.txt` section 3) is supplied through `AdsOptions.configProvider`; missing fields come from
  the placement default, bad/empty JSON = default, no default = off, `tiers.normal[].enable` missing = true.
- Formats: `Ads.interstitial.showThen(...)`, `Ads.rewarded.request(...)`, `Ads.native`, `Ads.banner`, `Ads.isPremium`.
- Test mode: `AdsOptions.debug = true` and `testDeviceIds`; use Google test ad unit ids.
- `revenueStore` / `capStore` default to in-memory: caps reset on process death. Provide your own to persist.
- `ageRestricted` is not supported yet.

## Sample app and smoke test
`sample` has one demo screen per format plus an event log. Run `./gradlew :sample:installDebug`; the steps and
result table are in `docs/sample-smoke.md`.
