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
