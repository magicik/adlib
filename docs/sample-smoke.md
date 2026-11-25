# Sample app smoke test

Run: `./gradlew :sample:installDebug`, then
`adb shell am start -n com.example.adlib.sample/.MainActivity`. Walk each screen, save a screenshot with
`adb exec-out screencap -p > docs/sample-smoke/<screen>.png`, and capture logcat with
`adb logcat -s AdLibSample AdThreadProbe`.

Build: `./gradlew :sample:assembleDebug` - pass (via `./scripts/check.sh`).

Result key: pass / fail / not-run. Evidence = screenshot or logcat line.

Run 2026-11-24 on Pixel 6a (GR0023624121JE), Google test ad units, driven via adb. Screenshots and
`logcat.txt` (tags AdLibSample, AdThreadProbe) are in `docs/sample-smoke/`. No ad was tapped.

| Format | loaded | shown | dismissed | gone on premium | showThen action ran | reward earned | app-open on return |
|--------|--------|-------|-----------|-----------------|---------------------|---------------|--------------------|
| Native | pass - Loaded event | fail (by design) - HiddenOnPurpose (R27b), grid never rendered (native.png) | n/a | not-run | n/a | n/a | n/a |
| Banner | pass - Loaded | pass - test banner at bottom (banner.png) | n/a | not-run | n/a | n/a | n/a |
| Interstitial | pass - Loaded | pass - full screen test ad (interstitial.png) | pass - X closes, Dismissed | pass - premium on: action ran, no new Shown | pass - "showThen action ran: navigate", navigated home | n/a | n/a |
| Rewarded | pass - Loaded | pass - video test ad (rewarded.png) | pass - Dismissed, "Ad closed" | not-run | n/a | pass - "Reward earned: unlocked!" (rewarded-after.png) | n/a |
| App Open | pass - Loaded | pass - shown after HOME and return (appopen-ad.png) | pass - Dismissed | not-run | n/a | n/a | pass; share sheet then BACK showed no ad (pauseForExternalFlow) |

Other rows:
- Consent: not-run - no UMP form appeared (non-EEA device); SDK initialised and ads loaded.
- Home: pass (home.png). Event log: pass - events show in-app and in logcat.
- Native grid 8-slot cap: not-run - blocked by the finding below.
- App Open overlay on first return and Back-block during overlay: not-run (overlay too brief to drive by adb).
- Thread probe screen: not-run (T13 already measured).
- Crashes: none in the crash buffer.

BUGS_FOUND (design question, not a crash):
- Native never renders on a fresh device with test units. R27b hides a loaded native until the first paid impression with
  revenue > 0, but test ads report `Paid 0.0 USD` (banner, interstitial, rewarded, app open all 0.0), so the gate never opens
  and the sample native grid cannot be exercised. Repro: fresh install, open Native: log alternates Loaded / HiddenOnPurpose.
  Owner to decide: sample bypass flag for TestMode, or exempt test ads from R27b.

## Run 2026-11-25 (Pixel 6a GR0023624121JE, Google test ad units, no ad tapped)

Evidence files are `docs/sample-smoke/run2-*`. Driven by adb; Back on the App Open overlay not conclusively caught.

| Check | Result | Evidence |
|-------|--------|----------|
| 1. T18 daily cap persistence | could-not-run - the sample sets no dailyCap (`FullScreenOptions` only sets `minGapAfterFullScreenMs`; `dailyCap` defaults to 0 = unlimited) | `sample/.../SampleApp.kt`; finding below |
| 2. T22 auto attach | pass - no attach switch in the sample (grep of `sample/src` finds no attach); 1st return: overlay + spinner, then `Shown` (Loaded 13:48:05.324, Shown .382); 3rd return (after 20 s): `Shown` at once, no overlay (overlay only the first time) | run2-return1-1/3/6.png, run2-return3-*.png, run2-appopen-screen.png |
| 2b. 2nd return (about 8 s after dismiss) | no ad, no event - consistent with the 15 s `minGapAfterFullScreenMs` set in SampleApp (inferred, not proven) | run2-return2-final.png |
| 2c. Back blocked during overlay | not-run / inconclusive - overlay gone before the Back press; Back sent while the ad was up did not close it | run2-return1-afterback.png |
| 3. Native grid after T16 | pass (renders) - test native ads render in the grid, R27b no longer hides them. 8-slot cap not measured: uiautomator dump of the animated grid failed (66-byte dump) and scrolling shows ads to the end; use the Shown count | run2-native.png, run2-native-bottom.png, run2-native-logcat.txt |
| 4. Gone on premium | pass - App Open: `Dropped(reason=FLUSHED)`, no ad on return; Banner: empty (no banner at bottom); Rewarded: "Ad available: false", Unlock disabled. Interstitial already passed on 2026-11-24 | run2-premium-on/return/banner/rewarded.png |
| 5. Thread probe | pass - see table below | run2-threadprobe-logcat.txt |
| 6. UMP on fresh install | pass - `pm clear`, EEA debug form shown, Consent tapped, home shown | run2-ump-form.png, run2-home-after-consent.png |

Thread probe (raw SDK, probe screen): every callback is on a `GMA(BG) n` thread, main=false. Seen: init adapter listener, interstitial/rewarded onAdLoaded,
native onNativeAdLoaded, banner onAdLoaded/onAdImpression/onAdPaid, inter onAdShowed/onAdImpression/onAdPaid/onAdDismissed,
rewarded onAdShowed/onAdImpression/onAdPaid/onUserEarnedReward/onAdDismissed. Not captured: native onAdPaid/onAdImpression (probe native ad is loaded but never rendered),
onAdFailedToShow (never fails with test ads).

BUGS_FOUND / findings (not fixed):
- Sample cannot exercise dailyCap (no placement or options set it). Owner: add a low dailyCap to the sample or a test hook.
- AdMob native ad validator popups ("No implementation issues found") pile up on the Native screen, stay on top after leaving it and block taps on the Probe screen until the app is force-stopped. Debug overlay of the SDK, not lib code; consider disabling the validator in the sample.
- 2nd App Open return within the 15 s gap shows nothing and logs nothing (no `Dropped`/skip event), so the skip reason is not visible in the event log.
