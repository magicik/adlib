# Sample app smoke test

Run: `./gradlew :sample:installDebug`, then
`adb shell am start -n com.example.adlib.sample/.MainActivity`. Walk each screen, save a screenshot with
`adb exec-out screencap -p > docs/sample-smoke/<screen>.png`, and capture logcat with
`adb logcat -s AdLibSample AdThreadProbe`. Do this in the same session as T13 (thread measurement).

Build: `./gradlew :sample:assembleDebug` - pass (via `./scripts/check.sh`).

Result key: pass / fail / not-run. Evidence = screenshot or logcat line.

| Format | loaded | shown | dismissed | gone on premium | showThen action ran | reward earned | app-open on return |
|--------|--------|-------|-----------|-----------------|---------------------|---------------|--------------------|
| Native | not-run - no device | not-run | n/a | not-run | n/a | n/a | n/a |
| Banner | not-run - no device | not-run | n/a | not-run | n/a | n/a | n/a |
| Interstitial | not-run - no device | not-run | not-run | not-run | not-run | n/a | n/a |
| Rewarded | not-run - no device | not-run | not-run | not-run | n/a | not-run | n/a |
| App Open | not-run - no device | not-run | not-run | not-run | n/a | n/a | not-run |
