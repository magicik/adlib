# AdLib

Android ads library on top of the Google Mobile Ads Next-Gen SDK (`ads-mobile-sdk`) and UMP.
Design (source of truth): `BookReader/docs/ads-lib-phase1-v3.txt`.

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
