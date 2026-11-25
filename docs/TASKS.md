# AdLib tasks (GD2)

The board for the agent team (replaces GitHub/GitLab issues). Design: `docs/ads-lib-phase1-v3.txt` (v3 wins).
Requirement codes (R/P/H): `docs/history/ads-lib-phase1-v1.txt` 2.2 + `docs/history/ads-lib-phase1-v2-addendum.txt`.
Old library (read-only reference for "like the old lib" details): `D:\Workspace\Google_Dev\BTN\Project\AdsExample\ads`.

## Conventions
- One `## T<n> — <title>` block per task, fields in this order:
  `- Status:` backlog | ready | in-progress | review | blocked | done
  `- Type:` feature | bug | chore | test · `Size:` s (<2h) | m (half day) | l (must be split) · `Complex:` yes | no · `Priority:` p0 | p1 | p2 · `Depends on:` T<x>, … | none
  then `### Goal`, `### Acceptance criteria` (testable checkboxes), `### Out of scope`, `### Files likely touched`, `### Log`.
- Bug tasks: `### Goal` holds Steps to reproduce / Expected / Actual / Evidence.
- Only the tracker, the orchestrator and `scripts/merge-task.sh` edit this file, only on `main`. Dev never edits it on a branch.
- `blocked` with a log line `owner:` = waiting for the owner; the tracker never promotes it.
- Branch per task: `<type>/T<n>-<slug>`; commits end with `(T<n>)`.
- Dropped by owner 2026-11-23: LegacyConfigReader + ColorVerse migration (the lib will be applied to another app by the owner; it is tested in `sample` here).
- Already done before this board (2026-11-23): scaffold, ConfigParser + PlacementConfig, AdGateway/SdkAdGateway/MainSerialDispatcher/Clock, FakeAdGateway/FakeClock, ConsentGate/UmpConsentSource, AdsBootstrap/MobileAdsInitializer, FakeConsentSource/FakeSdkInitializer.

---

## T1 — Placement registry, config lookup, Premium, kill switch, events
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: none
### Goal
The process-level state every later component reads: registered placements, per-load config, Premium, kill switch, and the event stream (v3 sections 3, 4, 6.6; R5, R8, R19-R21, R48).
### Acceptance criteria
- [ ] `Placement(name, format, poolKey, default: PlacementDefault?)`; a registry built from a list rejects duplicate names and two placements sharing a poolKey with different formats: throws in debug, logs a warning event in release (v3 4).
- [ ] `ConfigProvider { placementName -> json? }` is read on EVERY lookup (R8); lookup = `ConfigParser.parse(json, placement.default)`; parser warnings go to the event listener (replace `ConfigWarningSink.None`).
- [ ] Premium: `setPremium(Boolean)` + `isPremium: StateFlow<Boolean>`, initial value supplied at construction (stored value, T0 sync, no I/O on main).
- [ ] Kill switch: `setKillSwitch(Boolean)`; `isAdAllowed(placement)` = !premium && !killSwitch && config.enable && canRequestAds — one function used at load AND at show (v3 6.6). Table-driven test over the combinations.
- [ ] `AdsEvent` sealed type + `AdsEventListener`: at least Loaded, LoadFailed, Dropped(reason: late/expired/flushed/capped), Shown, Dismissed, Clicked, Paid(AdPaid), ConfigWarning, WatchdogFired. Listener calls happen on the serial dispatcher; a throwing listener does not break the lib.
### Out of scope
`Ads.install` facade (T11), pool, test mode.
### Files likely touched
`ads-core/.../core/placement/` (new), `ads-core/.../core/state/` (new: Premium, KillSwitch, AdsState), `ads-core/.../core/events/` (new), `core/config/ConfigParser.kt` (sink), tests in `ads-core/src/test/.../`.
### Log
- 2026-11-23 merged 683861c; QA rounds 1; owner-check:none

## T2 — Test mode (R22-R27)
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T1
### Goal
Lib-side test mode, as in the old library (no SDK API says "this is a test device"). v3 6.5, v1 2.3, v2 A.9.
### Acceptance criteria
- [ ] Read the old lib's test-mode code first (read-only) and port its detection rule; write the rule in 2-4 lines in `docs/ARCHITECTURE.md`. If the old rule needs something v3 does not have, stop and report it under NOTES_FOR_OWNER instead of inventing one.
- [ ] `testDeviceIds` are passed to `RequestConfiguration.setTestDeviceIds` BEFORE init (hook into `MobileAdsInitializer.configure`).
- [ ] `effectiveInterstitialIntervalMs(configMs)` = max(configMs, 60_000) when test mode, else configMs (R26). Table test.
- [ ] Native refresh is disabled in test mode (expose a flag/function T9 uses) (R26).
- [ ] R27a: a native whose headline contains "Test Ad" marks test mode.
- [ ] R27b: revenue accumulator sums `valueMicros` per currency (Long, never across currencies), persisted via an injectable store (fake in ads-testing); while the total is 0 a native is hidden until its first paid impression — emits an explicit event/log saying "loaded but hidden on purpose (R27b)".
- [ ] `gone_in_test_mode` applies to native / interstitial / app open, NOT rewarded (Q-C) — one table test.
### Out of scope
UI hiding itself (T9 uses the flag).
### Files likely touched
`ads-core/.../core/testmode/` (new), `core/bootstrap/SdkInitializer.kt`, `ads-testing/.../FakeRevenueStore.kt` (new), tests.
### Log
- 2026-11-23 merged 6ec8cd5; QA rounds 1; owner-check:needed (test device ids on device; shouldHideNative after a zero-value paid event; dropped "Ad Test" variant)

## T3 — AdPool (waiter-driven single-flight, tiers, expiry)
- Status: done
- Type: feature · Size: m · Complex: yes · Priority: p1 · Depends on: T1
### Goal
The self-made pool for interstitial / rewarded / app open / native (no Preloader; banner is NOT pooled). v3 6.1, 6.3, Q-B; P1-P14, H1-H5.
### Acceptance criteria
- [ ] Key = (poolKey, format); entries hold handle, tier, loadedAt, placement; taking an ad is atomic (P12). All state on the serial dispatcher, no locks.
- [ ] Every load first calls `AdsBootstrap.awaitReadyToLoad()`; false -> NoAd immediately (R11).
- [ ] Waiter counting: runs min(waiters, parallel) loads at once (single-flight per LOAD, not per key) - 8 native waiters on one key do not fill sequentially. Test with `FakeAdGateway` random delays and random order (seeded), incl. "never answers".
- [ ] Each waiter has its own timeout; a cancelled waiter does not cancel the load; a result arriving after all waiters left goes to the pool if below maxPerKey, else `destroy()` + `Dropped(late)`.
- [ ] Normal tiers tried in order with each tier's `timeout_ms`, total bounded by `total_timeout_ms` (high floor not counted, Q-B).
- [ ] High floor: only for background preload with no waiter, never for Native or reactive App Open; safety cap 30s only to free the load slot; permit released in `finally`; never acquired twice in one load when parallel=1.
- [ ] maxPerKey respected; when full drop normal before high floor, older before newer; expiry: app open 4h, others 55 min (`Clock`), expired entries destroyed with `Dropped(expired)`.
- [ ] Premium on -> flush pool (destroy all, `Dropped(flushed)`).
- [ ] Uses the ONE `core.time.Clock` (no second clock type).
### Out of scope
Show logic, banner, UI.
### Files likely touched
`ads-core/.../core/pool/` (new), `ads-testing/.../FakeAdGateway.kt` (if a behavior is missing), tests.
### Log
- 2026-11-23 merged 3c4c99d; QA rounds 1; owner-check:none; nits filed as T15

## T4 — FullScreenGate + watchdog
- Status: done
- Type: feature · Size: m · Complex: yes · Priority: p1 · Depends on: T1
### Goal
One gate for every full-screen ad (interstitial, rewarded, app open) so two never overlap and a lost dismiss never blocks ads forever. v3 6.2, 6.4, Q-A, Q-H.
### Acceptance criteria
- [ ] States Idle | Reserved(owner) | Showing(owner); reserve/show/release transitions tested, a second reserve while not Idle is refused.
- [ ] Watchdog: after show, with no dismissed/failed callback, the gate is released when a non-ad Activity of the app reaches ON_RESUME, or after 2 min (configurable); emits `WatchdogFired`. Virtual time test.
- [ ] `minGapAfterFullScreenMs` (default 60s) applies after ANY full-screen closes; App Open is not counted in interstitial dailyCap / oneInN (Q-A) - the counters live with T5, the gap lives here.
- [ ] Premium on while Reserved -> released, owner notified.
- [ ] Activity tracking via an `ActivityTracker` abstraction (fake in ads-testing); no Activity held strongly in the gate.
### Out of scope
Format-specific show code (T5/T6/T7).
### Files likely touched
`ads-core/.../core/fullscreen/` (new), `ads-testing/.../FakeActivityTracker.kt` (new), tests.
### Log
- 2026-11-23 merged 79b271f; QA rounds 1; owner-check:needed (device: real AdActivity resume vs watchdog; confirm rewarded ignores the gap)

## T5 — Interstitial: preload + showThen + caps
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T2, T3, T4
### Goal
`Ads.interstitial.preload(placement)` and `showThen(activity, placement) { action() }` where the action ALWAYS runs. v3 4, 6.2, 6.4, 6.6; R28-R31.
### Acceptance criteria
- [ ] `action` runs exactly once in every branch: ad shown then dismissed, no ad, load error, show error, capped, timeout, gate busy, premium, kill switch. One table-driven test.
- [ ] Host recreated during the flow: action waits until the new host is STARTED (never runs on a dead host, never dropped).
- [ ] Recheck at show (v3 6.6) via T1 `isAdAllowed` + expiry.
- [ ] Caps: dailyCap, oneInN, interval = T2 `effectiveInterstitialIntervalMs`, plus the T4 gap. Day boundary via `Clock`.
- [ ] Retry after a failed preload follows the old lib (v1 R28-R31 says 5s/15s); read the old lib to confirm the exact rule, report any difference in NOTES_FOR_OWNER.
- [ ] (from T4 QA) The gate reservation is released on EVERY branch, including owner cancelled and host destroyed (the gate has no Reserved timeout by design); test it. Premium already true at reserve time is rechecked (no callback fires for an old value).
- [ ] Respect `FullScreenGate.isWithinGap()` before reserving.
### Out of scope
Splash (not in GD2).
### Files likely touched
`ads-core/.../core/fullscreen/interstitial/` (new), tests.
### Log
- 2026-11-23 merged 06fefd4; QA rounds 3 (dev x2 -> dev-senior); owner-check:needed (device: rotate during ad, back/background during ad; awaitLiveHost unbounded by design; old lib had no retry rule, v1 5s/15s used)

## T6 — Rewarded
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T3, T4
### Goal
`Ads.rewarded.isAvailable/isAllowed(placement): Flow<Boolean>` and `request(activity, placement, onEarned, onClosed, onUnavailable)`. v3 4, 6.2; R32 (waiting screen, timeout 10s, 3 separate callbacks).
### Acceptance criteria
- [ ] Exactly one of onUnavailable / (onEarned? then onClosed) per request in every branch (no ad, timeout 10s, show error, premium/kill switch, gate busy). Table test.
- [ ] While waiting for a load the gate is Reserved (the "waiting screen" owner); release on every exit.
- [ ] `isAllowed` reflects premium/kill switch/canRequestAds/config live; `isAvailable` reflects a non-expired pooled ad.
- [ ] `gone_in_test_mode` does NOT apply (Q-C).
- [ ] Uses `SdkAdGateway.showRewarded` (SDK 1.7.0 single load/show signature).
- [ ] (from T4 QA) The gate reservation is released on EVERY branch, including owner cancelled and host destroyed (the gate has no Reserved timeout by design); test it. Premium already true at reserve time is rechecked (no callback fires for an old value).
- [ ] Rewarded ignores the gap (user-initiated, as the old lib) — pending owner confirmation, see T4 log.
### Out of scope
Waiting-screen UI (app supplies it; lib exposes state).
### Files likely touched
`ads-core/.../core/fullscreen/rewarded/` (new), tests.
### Log
- 2026-11-23 merged 5a0df1b; QA rounds 2; owner-check:needed (device: onEarned before onClosed; reward after watchdog release needs owner decision; isAllowed/isAvailable poll 1s; rewarded preload has no retry)

## T7 — App Open on resume: rules and state machine
- Status: done
- Type: feature · Size: m · Complex: yes · Priority: p1 · Depends on: T3, T4
### Goal
The mandatory App Open on return to the app (R33), logic only (overlay view = T8). v3 7, Q-D, Q-E, Q-H.
### Acceptance criteria
- [ ] `attachResume(application)` with injectable `ForegroundSource` / `ActivityTracker` / `Clock` (fakes in ads-testing).
- [ ] First return of the PROCESS with no cached ad: reactive load, request the overlay (T8 interface), show as soon as the ad arrives if still foreground within `reactiveShowTimeout` (value from the old lib) and within `overlayMaxMs` (hard cap, not configurable to infinite). ARMED after that first try whatever the result (Q-E).
- [ ] Later returns: show only a cached ad, never block, never reactive-load; no cached ad -> silent preload for next time. After a show, preload one.
- [ ] Show conditions: background >= `minBackgroundMs` (default 0), `isAdAllowed`, ad < 4h, gate Idle, not within the full-screen gap.
- [ ] Skip the next resume after: a native/banner/inter click; consent/privacy form showing (`ConsentGate.formShowing`); `pauseForExternalFlow(maxMs = 60_000)` (expires by itself, one return only); Activity in `excludedActivities`.
- [ ] Fix carried from ConsentGate: if a consent form never reports dismissed (host destroyed), `formShowing` must not pause App Open forever - clear it when the form's host Activity is destroyed (add the hook to ConsentGate) and test it.
- [ ] Scenario tests (virtual time): first return no-fill, background mid-load, ad after timeout (cached for next), click then return, form open, pause never resumed, rotation keeps state.
- [ ] (from T4 QA) The gate reservation is released on EVERY branch, including owner cancelled and host destroyed (the gate has no Reserved timeout by design); test it. Premium already true at reserve time is rechecked (no callback fires for an old value).
- [ ] Respect `isWithinGap()`. Call `show()` so that no host `onResume` follows it (ProcessLifecycle ON_START runs before the host onResume: a show at ON_START lets the watchdog free the gate while the ad is still coming up). Test this ordering with FakeActivityTracker.
- [ ] Reuse ONE `AppActivityTracker` per process (from T4) and extend that interface for top-Activity / excludedActivities.
### Out of scope
Overlay drawing and Back handling (T8), Splash.
### Files likely touched
`ads-core/.../core/appopen/` (new), `core/consent/ConsentGate.kt`, `ads-testing/` fakes, tests.
### Log
- 2026-11-23 merged 0d8b2c2; QA rounds 1; owner-check:none (device check when T11 wires it: show after onResume, no WatchdogFired; any Clicked skips next return)

## T8 — App Open overlay + Back blocking
- Status: done
- Type: feature · Size: s · Complex: no · Priority: p1 · Depends on: T7
### Goal
Full-screen scrim + progress on the first return, Back swallowed while it shows (owner Q-F). v3 7 (M7, R2/R3).
### Acceptance criteria
- [ ] Overlay bound to ONE host Activity via WeakReference; removed on host stop/destroy, on premium, on ad shown, on error, on `overlayMaxMs`.
- [ ] Back is consumed only while the overlay is visible (OnBackPressedDispatcher callback enabled/disabled with it) - unit-test the enable/disable logic; visual: manual.
- [ ] Ad is shown on the current top Activity after rechecks, not necessarily the overlay host.
- [ ] App can replace the overlay content (default provided by the lib).
### Out of scope
Compose-specific overlay.
### Files likely touched
`ads-core/.../core/appopen/` (overlay controller), maybe a small View in `ads-core` (no Compose), tests.
### Log
- 2026-11-23 merged 552ce46; QA rounds 1; owner-check:needed (overlay/Back on device)

## T9 — ads-ui-compose: Native
- Status: done
- Type: feature · Size: m · Complex: yes · Priority: p1 · Depends on: T2, T3
### Goal
`NativeAd(placement, adIndex, loading) { ad -> }` for Compose using Google's compose_utils (copied, Apache-2.0 kept). v3 4, 8; `docs/compose-utils-notes.md`.
### Acceptance criteria
- [ ] Copy `NativeComposeUtility.kt` (Next-Gen version) with license header + NOTICE; note source URL/commit in the file.
- [ ] Slot states Idle | Loading | Shown | Gone; placeholder keeps size while Loading; Gone on error/disabled/premium/test-mode rules (R3, R20, R41).
- [ ] Ads held per (screenScope, adOrdinal) in a ViewModel-scoped holder (NavBackStackEntry): rotation keeps ads, `onCleared` destroys them (M5).
- [ ] Cap = max(8, visible + 1), release by LRU of "last visible" (F3) with `destroy()`; scrolling back takes a new ad from the pool. Holder logic unit-tested.
- [ ] Attribution "Ad" via `NativeAdAttribution` with a translatable string resource; all assets inside NativeAdView; no click handlers on native views.
- [ ] Sample layouts: large, small (compact) at minimum. Visual: manual check + screenshot noted for owner.
### Out of scope
ads-ui-view (later), native refresh beyond the test-mode flag.
### Files likely touched
`ads-ui-compose/src/main/...` (new files), `ads-ui-compose/build.gradle.kts` (Compose deps - protected path, owner merges), tests.
### Log
- 2026-11-23 QA APPROVED 70b989a round 1; waiting for owner merge: protected paths (ads-ui-compose/build.gradle.kts, gradle/libs.versions.toml); owner-check:needed
- 2026-11-24 merged 70b989a as 097eb8b; QA rounds 1; owner-check:needed

## T10 — ads-ui-compose: Banner
- Status: done
- Type: feature · Size: s · Complex: no · Priority: p1 · Depends on: T1
### Goal
`BannerAd(placement)` composable: direct load per View, no pool (v3 0, Q14), `AndroidView` + `SdkAdGateway.loadBanner`.
### Acceptance criteria
- [ ] Load only if `isAdAllowed`; Gone on error/premium; destroy on dispose.
- [ ] Width-aware anchored adaptive size; no space reserved after a failure (R3).
- [ ] Paid/click events forwarded (click also feeds T7 "skip next resume").
### Out of scope
Collapsible banners, refresh rules beyond SDK defaults.
### Files likely touched
`ads-ui-compose/src/main/...`, tests for the state logic only.
### Log
- 2026-11-23 QA APPROVED f2eb6b7 round 1; stacked on T9 branch; waiting for owner merge after T9 (protected paths come from T9); owner-check:needed
- 2026-11-24 merged f2eb6b7 as e68c2e9; QA rounds 1; owner-check:needed

## T11 — Ads.install facade + AdsOptions
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T1, T2, T3, T4, T5, T6, T7
### Goal
The public entry point of v3 section 4: `Ads.install(application, AdsOptions(...))`, main process only (L6), wiring ConsentGate, AdsBootstrap, pool, gates, events.
### Acceptance criteria
- [ ] `Ads.install` is idempotent, runs only in the main process, sync and without network (T0).
- [ ] `Ads.consent.gather(activity)` (Q-G), `canRequestAds`, `privacyOptionsRequired`, `showPrivacyOptions(activity)` delegate to ConsentGate.
- [ ] `Ads.setPremium/isPremium/setKillSwitch`, `Ads.interstitial`, `Ads.rewarded`, `Ads.appOpen` exposed as v3 section 4.
- [ ] Early validation errors from T1 surface at install.
- [ ] One integration test with all fakes: install -> consent -> init -> preload -> showThen.
- [ ] Wire DefaultAppOpenOverlay as the default AppOpenOverlay; declare androidx.activity explicitly in ads-core/build.gradle.kts (from T8 QA)
- [ ] Create NativeController(pool, gateway, state, testMode, bus, serialScope) and provide it as LocalNativeAdSource (e.g. Ads.native) (from T9)
- [ ] Provide BannerAdSource: BannerController(gateway, state, testMode, bus, bootstrap::awaitReadyToLoad, scope) as LocalBannerAdSource (from T10)
### Out of scope
LegacyConfigReader (dropped, owner 2026-11-23).
### Files likely touched
`ads-core/.../core/Ads.kt`, `AdsOptions.kt` (new), tests.
### Log
- 2026-11-23 QA APPROVED 91b0aad round 1; stacked on T10 (on T9); waiting for owner merge after T9, T10 (protected: ads-core/build.gradle.kts, ads-ui-compose/build.gradle.kts, gradle/libs.versions.toml); owner-check:needed
- 2026-11-24 merged 91b0aad as fa27d4e; QA rounds 1; owner-check:needed

## T12 — Sample app: demo UI for every format
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T8, T9, T10, T11
### Goal
Draw the demo UI in `sample` (Compose + Material 3, already in its dependencies) so every format can be tried by hand in this repo, using the real public API and Google test unit ids (v3 2). This app is where the library is tested; other apps adopt it later (owner 2026-11-23).
### Acceptance criteria
- [ ] `Ads.install` in the sample `Application` with one placement per format (test unit ids), `debug = true`, test mode on; `Ads.consent.gather(activity)` in the first Activity.
- [ ] Home screen: list of demo screens + toggles for Premium and kill switch + "Privacy options" button (shown only when `privacyOptionsRequired`).
- [ ] Native screen: LazyVerticalGrid/LazyColumn with >= 12 native slots (to exercise the 8-slot cap and LRU) using the lib layouts (large + small).
- [ ] Banner screen: anchored adaptive banner at the bottom.
- [ ] Interstitial screen: button "Next" using `showThen { navigate }` — navigation happens in every outcome.
- [ ] Rewarded screen: unlock button enabled by `isAllowed`, shows the earned reward / unavailable state.
- [ ] App Open: switch to attach/detach resume, button `pauseForExternalFlow` + one "external flow" action (share sheet) to try the skip rules.
- [ ] Event log panel (last ~50 `AdsEvent`s, incl. revenue) visible on every screen, plus logcat tag `AdLibSample`.
- [ ] All user-facing strings in `res/values/strings.xml`; no new dependencies (if one is truly needed: version catalog + reason, owner merges).
- [ ] `./scripts/check.sh` passes (it assembles `sample`). Visual criteria: manual note + screenshots in T14.
### Out of scope
Splash, ads-ui-view, publishing.
### Files likely touched
`sample/src/main/kotlin/com/example/adlib/sample/` (new screens, `SampleApp.kt`), `sample/src/main/res/values/strings.xml`, `sample/src/main/AndroidManifest.xml` (Application class, APPLICATION_ID meta-data — protected path, owner merges).
### Log
- 2026-11-24 QA APPROVED 755d90a round 1; waiting for owner merge: protected path (sample/src/main/AndroidManifest.xml); owner-check:needed
- 2026-11-24 merged 755d90a as b6ea320; QA rounds 1; owner-check:needed
- 2026-11-24 owner decision: App Open stays attach-only (no detach API); pauseForExternalFlow already covers system settings / billing flows.

## T13 — Measure real callback threads on a device
- Status: done
- Type: chore · Size: s · Complex: no · Priority: p1 · Depends on: none
### Goal
Install `sample`, filter logcat tag `AdThreadProbe`, record the thread of load/show/onAdPaid/init callbacks, then finalize v3 6.7.
### Acceptance criteria
- [ ] Table of callback -> thread in `docs/ARCHITECTURE.md`; v3 6.7 updated.
### Out of scope
Code changes (unless a callback is proven to need different handling -> new task).
### Files likely touched
`docs/`.
### Log
- 2026-11-23 owner: needs a real device or an installed emulator (`adb devices` empty, `Sdk/emulator/emulator.exe` missing).
- 2026-11-24 device connected: Pixel 6a
- 2026-11-24 merged 6670a0f as e06a58d; Rounds: 0 (no QA); measured on Pixel 6a; owner-check:none

## T14 — Self-test in the sample app + README
- Status: done
- Type: test · Size: s · Complex: no · Priority: p1 · Depends on: T12
### Goal
Build and test the library inside this repo's `sample` app, then document it. No other app is touched.
### Acceptance criteria
- [ ] `./gradlew :sample:assembleDebug` succeeds (also covered by check.sh).
- [ ] If `adb devices` shows a device/emulator: `./gradlew :sample:installDebug`, launch it (`adb shell am start -n com.example.adlib.sample/.MainActivity`), go through each demo screen, capture one screenshot per screen (`adb exec-out screencap -p > docs/sample-smoke/<screen>.png`) and the `AdLibSample` + `AdThreadProbe` logcat lines.
- [ ] `docs/sample-smoke.md`: checklist per format (loaded / shown / dismissed / gone on premium / showThen action ran / reward earned / app-open on return), result pass/fail/not-run with the evidence line or screenshot.
- [ ] No device: build part done, every device row `not-run — no device`, report `DEV: CHECK PASS` for the build and put "device smoke test needed" in NOTES_FOR_OWNER (T13 has the same device requirement — do both in one device session).
- [ ] `README.md`: install, consent, placements + config schema, test mode, sample app, how to run the smoke test; publishing groupId still PLACEHOLDER; ktlint/detekt not added yet.
- [ ] README: call Ads.appOpen.attachResume(); revenueStore/capStore default in-memory (caps reset on process death); ageRestricted not supported yet (from T11 QA)
### Out of scope
Code fixes (file each failure as a bug task via the orchestrator).
### Files likely touched
`docs/sample-smoke.md` (new), `docs/sample-smoke/` (new), `README.md`.
### Log
- 2026-11-24 merged a4d64c0 as 0c6dc42; Rounds: 0 (no QA); owner-check:needed (device smoke with T13)

## T15 — Hardening: pool total-timeout leak window, gateway exceptions, idempotent destroy
- Status: done
- Type: bug · Size: m · Complex: no · Priority: p2 · Depends on: none
### Goal
Two small defects QA found in T3 (non-blocking there).
Steps / Actual: (1) `AdPool.kt` ~174-179: a tier result is returned inside `withTimeoutOrNull(totalTimeoutMs)`; if the total timeout fires just after an attempt produced its `PoolEntry`, the block returns null and the loaded handle is never destroyed (leak). (2) If a gateway `load*` call throws a non-cancellation exception inside `launchLoad`, the exception reaches the SupervisorJob / uncaught handler.
Expected: no loaded handle is ever lost; a throwing gateway ends that load as a failure with `AdsEvent.LoadFailed` and never crashes.
### Acceptance criteria
- [ ] A test that reproduces (1) deterministically (e.g. fake gateway delivering exactly at the total deadline) fails before the fix; after the fix the handle is pooled or destroyed + Dropped.
- [ ] A test with a fake gateway that throws: waiters get NoAd within their timeout, `LoadFailed` emitted, counters/permits restored, no uncaught exception.
- [ ] (optional, same PR) test for "waiter cancelled in the same instant an ad is handed over".
- [ ] (from T5 QA) `destroy()` is idempotent for every handle (flag in `SdkHandle` / fake), so a late SDK `onFailedToShow` after a throwing show cannot destroy a real ad twice; test with the fake.
- [ ] (from T7 QA) Move the duplicated "take as a child, destroy an arrived-but-cancelled handle" code of T5/T6/T7 into ONE AdPool helper, with a deterministic test of the hand-over/cancel interleaving (QA mutation: removing that destroy is currently NOT caught by any test).
- [ ] (from T7 QA) `AppOpenResumeController.tryShow`: an app overlay whose `show()` throws must not crash (catch, release gate, emit an event).
- [ ] DefaultAppOpenOverlay AndroidSurface.add(): if a call after addView throws, the frame is removed (session calls surface.remove() on add failure); test asserts remove() called (from T8 QA)
### Out of scope
Eager expiry timer (expiry stays lazy, bounded).
### Files likely touched
`ads-core/.../core/pool/AdPool.kt`, `core/fullscreen/interstitial|rewarded/*Controller.kt`, `core/appopen/AppOpenResumeController.kt`, tests, maybe `ads-testing/.../FakeAdGateway.kt` (throw behavior).
### Log
- 2026-11-23 filed from QA nits on T3.
- 2026-11-23 merged 4395efa; QA rounds 1; owner-check:needed (SdkHandle destroy idempotency, overlay frame removal on device); item (1) defensive fix only, no failing repro possible on serial dispatcher.

## T16 — Native never renders with test ads (R27b gate)
- Status: done
- Type: bug · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Device smoke on Pixel 6a (docs/sample-smoke.md, merged 62af001) shows a loaded native is hidden by R27b (hide until first paid impression with revenue > 0). Test ads report `Paid 0.0 USD`, so the gate never opens; the log alternates Loaded / HiddenOnPurpose(R27b). The sample cannot exercise the native grid or the 8-slot cap.
Steps to reproduce: fresh install, open the Native screen.
Expected: native grid renders in sample with test ads.
Actual: native hidden on purpose (R27b) every time; no revenue ever accumulates from test ads.
Evidence: docs/sample-smoke.md (Pixel 6a run); log alternates Loaded / HiddenOnPurpose(R27b).
OWNER DECISION (2026-11-24): R27b is bypassed ONLY when the ad being served is a test ad (test mode / test unit id / test device), regardless of build type. A release build or market-downloaded build that shows test ads also bypasses R27b. Real ads keep R27b unchanged. The bypass must NOT depend on the `debug` flag.
### Acceptance criteria
- [ ] Native grid renders in sample with test ads (manual check noted for owner, device).
- [ ] Unit test covers: test ad -> bypass; real ad -> still gated (table-driven).
### Out of scope
Changing R27b for real (non-test) ads; revenue store changes; any `debug`-flag-based bypass.
### Files likely touched
`ads-core/.../core/testmode/` (R27b gate), `sample/` (if option b), tests.
### Log
- 2026-11-24 filed from device smoke (Pixel 6a); owner decision needed (a) or (b).
- 2026-11-24 owner decision: bypass R27b only for test ads (any build type, not debug-flag based); real ads unchanged. Status in-progress.
- 2026-11-24 merged b3357cc as d64419f; QA rounds 1; owner-check:needed (native grid on device).

## T17 — UMP debug settings (test geography + test device)
- Status: done
- Type: feature · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Owner approved (2026-11-24) UMP `ConsentDebugSettings` support so the consent form can be tested on a non-EEA device. Default = none, so production behaviour is unchanged.
### Acceptance criteria
- [ ] `AdsOptions` gets an optional consent debug config: debug geography enum (none / EEA / NOT_EEA) + test device hashed ids; default = none (default options build no debug settings).
- [ ] `UmpConsentSource` passes it to `ConsentRequestParameters.Builder().setConsentDebugSettings(...)` (or an injectable seam); JVM test proves options reach the params builder / seam (no real UMP).
- [ ] Sample wires EEA geography + the Pixel 6a hashed id (read from logcat) or a toggle on Home.
- [ ] README documents the debug settings.
- [ ] Manual device check: sample on Pixel 6a shows the UMP form with EEA geography (owner).
### Out of scope
Any change to production defaults; App Open detach API (owner: stays attach-only).
### Files likely touched
`ads-core/.../consent/` (UmpConsentSource, AdsOptions), `sample/` (Home toggle or Application wiring), `README.md`, tests in `ads-core/src/test/`. Protected paths may be touched; owner merges if build files change.
### Log
- 2026-11-24 filed (owner approved UMP debug settings).
- 2026-11-24 in-progress (tracker).
- 2026-11-24 merged 5cda1d7 as 569f978; QA rounds 1; owner-check:none.

## T18 — Persist daily cap across process restarts (SharedPreferences)
- Status: done
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: none
### Goal
Owner decision 2026-11-25 (Q4=C): `dailyCap` must survive app kill/restart. Persist cap counters only (not revenue; revenue stays in memory). Use SharedPreferences, no new dependency.
### Acceptance criteria
- [ ] `CapStore` interface (read/increment daily count) with a SharedPreferences impl and an in-memory fake in `ads-testing`; existing cap logic uses the interface.
- [ ] Counter is keyed by local date; a new day resets it (virtual-time test with FakeClock across midnight).
- [ ] Count survives re-creating the cap object over the same store (simulated restart test).
- [ ] Clock moving backwards (user edits time) never makes the count negative or throws; document chosen behaviour in a test.
- [ ] `Ads.install` wires the SharedPreferences store by default.
### Out of scope
Persisting revenue; oneInN counters (stay in memory unless v3 says otherwise); DataStore.
### Files likely touched
`ads-core/.../cap/`, `ads-testing/`, `Ads.install` wiring, tests.
### Log
- 2026-11-25 filed (owner decided Q4=C).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged 3f1457a as 689c3e7; QA rounds 1; owner-check:needed (device: hit dailyCap, force-stop, relaunch, cap still holds)

## T19 — Rewarded counts toward interstitial gap (but is never blocked by gap)
- Status: done
- Type: feature · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Owner decision 2026-11-25 (Q5a=C): rewarded stays exempt from gap (user-initiated), but a shown rewarded must reset the gap timer so the next interstitial is not shown right after it.
### Acceptance criteria
- [ ] Rewarded show is never refused because of gap (existing behaviour kept, covered by a test).
- [ ] After a rewarded is shown, an interstitial request within `gap` is refused; after `gap` it is allowed (FakeClock test).
- [ ] App Open gap handling unchanged unless v3 says rewarded must affect it (check v3, note in report).
### Out of scope
Reward-after-watchdog behaviour (owner Q5b=A: reward may be lost, no change).
### Files likely touched
`ads-core/.../fullscreen/` (gate/gap state), tests.
### Log
- 2026-11-25 filed (owner decided Q5a=C, Q5b=A).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged ebba7e2 as 3a41f17; QA rounds 1; owner-check:none (no production change needed, test only)

## T20 — Test: showThen always runs `then` through total_timeout in every branch
- Status: done
- Type: test · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Owner decision 2026-11-25 (Q7=C): no new hard cap; instead prove `total_timeout` bounds the wait and `then` always runs.
### Acceptance criteria
- [ ] Table-driven virtual-time test over the wait branches (ad never arrives, load fails, consent never ready, pool empty, gate busy) asserting `then` runs exactly once no later than `total_timeout`.
- [ ] If any branch is found NOT bounded, stop and report to owner (do not silently add a cap).
### Out of scope
Production changes (unless a real unbounded branch is found, then owner decides).
### Files likely touched
Tests in `ads-core/src/test/` only.
### Log
- 2026-11-25 filed (owner decided Q7=C).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged 4a0c09b as 9e57cd3; no QA (test); owner-check:none; note: interstitial/rewarded wait is bounded by waitForAdMs (3s/10s defaults), not total_timeout; bound holds only while waitForAdMs <= total_timeout - owner may want a clamp

## T21 — App Open: click-skip flag expires after 60s
- Status: done
- Type: bug · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Today any `Clicked` skips the next App Open with no expiry, so a click an hour ago can suppress an unrelated resume. Owner decision 2026-11-25 (Q8=B): the skip flag is valid for 60s after the click, then ignored.
### Acceptance criteria
- [ ] Reproducing test (fails before fix): click, advance clock > 60s, resume -> App Open is allowed.
- [ ] Click then resume within 60s -> still skipped (existing behaviour).
- [ ] The 60s reuses the `pauseForExternalFlow` constant (no second magic number).
### Out of scope
Distinguishing in-app Custom Tab clicks from external ones.
### Files likely touched
`ads-core/.../appopen/`, tests.
### Log
- 2026-11-25 filed (owner decided Q8=B).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged 9c40c43 as 4e6a2ef; QA rounds 1; owner-check:none

## T22 — Ads.install auto-attaches App Open resume
- Status: done
- Type: feature · Size: s · Complex: no · Priority: p1 · Depends on: T21
### Goal
Owner decision 2026-11-25 (Q9=B): app must not need to remember `attachResume()`. `Ads.install` registers it automatically when an App Open placement is enabled; `attachResume()` stays public and a repeat call is a no-op.
### Acceptance criteria
- [ ] Verify `install` can reach `Application` for lifecycle registration; if not, report to owner before changing the API.
- [ ] Enabled App Open placement -> resume observer registered exactly once by `install`; none registered when disabled/Premium.
- [ ] Explicit `attachResume()` after install does not double-register (test).
- [ ] Opt-out flag in `AdsOptions` (default on) for apps that want manual control.
- [ ] Sample no longer calls `attachResume()` manually; README updated.
### Out of scope
A detach API (owner: App Open stays attach-only).
### Files likely touched
`Ads.install`, `AdsOptions`, `AppOpenResume` wiring, `sample/`, `README.md`, tests.
### Log
- 2026-11-25 filed (owner decided Q9=B).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged c0835b8 as 46136f5; QA rounds 1; owner-check:needed (device: install with App Open placement, no manual attach, background+return shows overlay+ad; autoAttachResume=false shows none)

## T23 — Clamp waitForAdMs to total_timeout (interstitial + rewarded)
- Status: done
- Type: bug · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Found by T20: the host wait for interstitial/rewarded is bounded by `waitForAdMs` (3s / 10s defaults), not `total_timeout`. If a caller sets `waitForAdMs > total_timeout`, `showThen` waits longer than the global bound. Owner decision 2026-11-25: effective wait = `min(waitForAdMs, total_timeout)`.
### Acceptance criteria
- [ ] Reproducing test (fails before fix): interstitial and rewarded with `waitForAdMs` > `total_timeout` run `then` by `total_timeout`, not later (FakeClock).
- [ ] Defaults unchanged (3s / 10s with 15s total_timeout) — existing tests still pass.
- [ ] Clamp applied in one place per controller (no duplicated min logic); KDoc on the option states the clamp.
### Out of scope
Changing default values; App Open overlayMaxMs.
### Files likely touched
`ads-core/.../fullscreen/interstitial/`, `.../rewarded/`, tests.
### Log
- 2026-11-25 filed (owner approved clamp after T20 finding).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged 08c02e8 as c1b512f; QA rounds 1; owner-check:none

## T24 — Add ktlint to check.sh
- Status: done
- Type: chore · Size: m · Complex: no · Priority: p2 · Depends on: none
### Goal
Owner approved ktlint (2026-11-25). Add ktlint (Gradle plugin, version in the version catalog) so `./scripts/check.sh` fails on style violations. detekt is NOT part of this task.
### Acceptance criteria
- [ ] ktlint plugin applied to all Kotlin modules (ads-core, ads-testing, ads-ui-compose, sample) with version in `gradle/libs.versions.toml`; reason stated in the Dev report.
- [ ] `./scripts/check.sh` runs `ktlintCheck` and fails on a violation (prove once with a deliberate violation, then remove it).
- [ ] Existing violations fixed with `ktlintFormat` (formatting only, no behaviour change; diff reviewed to be style-only). If a rule is too noisy (e.g. max line length vs existing code), relax it in `.editorconfig` with a one-line reason instead of mass rewrites.
- [ ] README (or docs) tells how to run `ktlintCheck` / `ktlintFormat`.
- [ ] `./scripts/check.sh` passes on the final commit; test count unchanged.
### Out of scope
detekt; any logic change; renames.
### Files likely touched
`build.gradle.kts` files, `gradle/libs.versions.toml`, `.editorconfig`, `scripts/check.sh`, README, many `*.kt` (format-only). Protected paths (build files, gradle/, scripts/) => owner merges with `OWNER=1`.
### Log
- 2026-11-25 filed (owner approved ktlint).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 waiting for owner merge: protected paths (build.gradle.kts, gradle/libs.versions.toml); branch chore/T24-ktlint @4ec1563; DEV CHECK PASS (125 tests); run OWNER=1 ./scripts/merge-task.sh chore/T24-ktlint 4ec156365d2fee7dab7f2b9ae91a5f6bedc99965
- 2026-11-25 merged 4ec1563 as 65135e3 (OWNER=1); no QA (chore); ktlint CLI 1.8.0 via root JavaExec (jlleitschuh plugin skipped .kt under AGP built-in Kotlin); owner-check:none; open: NativeComposeUtility.kt (Apache-2.0 copy) was reformatted, owner has not decided whether to exclude it from ktlint

## T25 — Comment cleanups (sample hashed id, AdsRuntime attach)
- Status: done
- Type: chore · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Owner approved (2026-11-25) two comment-only cleanups found by QA.
### Acceptance criteria
- [ ] `sample/.../SampleApp.kt`: the "remove for release" comment on the hardcoded UMP test device hashed id is replaced by one accurate line (sample-only debug/test device id; apps must use their own). No build-file or behaviour change.
- [ ] `AdsRuntime.kt`: the "Ads.install attaches once" comment is corrected to say the controller ignores repeat `attachResume()` calls.
- [ ] No other code change; `./scripts/check.sh` passes.
### Out of scope
Moving the id to local.properties/BuildConfig; ktlint-style fixes (T24 covers import order and line length).
### Files likely touched
`sample/.../SampleApp.kt`, `ads-core/.../AdsRuntime.kt` (comments only).
### Log
- 2026-11-25 filed (owner approved cleanup).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged fa44adf as 3cbcd7b; no QA (chore); owner-check:none; note: the "remove for release" text was in README.md:37, not SampleApp.kt; AdsRuntime comment was already accurate

## T26 — Config warnings emitted twice per show (state.config re-parses)
- Status: done
- Type: bug · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Steps: placement with broken config JSON, trigger an interstitial/rewarded show. Expected: one `ConfigWarning` per distinct problem. Actual: `AdsState.config()` re-parses the JSON on every call and re-emits warnings, so one show can report the same warning more than once (seen at `isAdAllowed`, `take`, and T23's new clamp call). Evidence: QA nit on T23.
### Acceptance criteria
- [ ] Reproducing test (fails before fix): broken config JSON, one show flow -> the same warning is emitted once (not per `config()` call).
- [ ] Parse result cached per placement and invalidated when the config JSON changes (test: new JSON -> re-parsed, new warnings emitted once).
- [ ] Existing config/warning tests still pass; no change to parsing rules.
### Out of scope
Changing ConfigParser semantics or default-merging rules.
### Files likely touched
`ads-core/.../AdsState.kt` (or wherever `config()` lives), tests.
### Log
- 2026-11-25 filed (owner approved, from QA nit on T23).
- 2026-11-25 in-progress (tracker)
- 2026-11-25 merged fba30ea as e9c1425; QA rounds 1; owner-check:none

## T27 — Add detekt with baseline
- Status: ready
- Type: chore · Size: m · Complex: no · Priority: p2 · Depends on: T24
### Goal
Owner approved detekt (2026-11-25). Static analysis in `./scripts/check.sh`, with a baseline so existing findings do not block; only NEW findings fail the check.
### Acceptance criteria
- [ ] detekt added (CLI via root task like ktlint in T24, or the Gradle plugin if it works with AGP 9 built-in Kotlin: verify it analyses `.kt` files of all 4 modules, not just `.kts`); version in `gradle/libs.versions.toml`, reason in the Dev report.
- [ ] `config/detekt/detekt.yml` (minimal overrides) and a generated baseline file committed; `./scripts/check.sh` runs detekt and fails on a new finding (prove once with a deliberate violation, e.g. `catch (e: Exception)` swallowing, then remove it).
- [ ] Rule `TooGenericExceptionCaught`/swallowed exceptions enabled so a `catch (Exception)` that can swallow `CancellationException` is flagged for new code.
- [ ] Noisy rules (magic numbers, long methods in tests, etc.) relaxed with a one-line reason each; no mass rewrite of existing code. Existing findings go in the baseline.
- [ ] Dev report lists the baseline size per rule so the owner can see what was parked.
- [ ] README says how to run detekt and update the baseline. Test count unchanged; `./scripts/check.sh` passes.
### Out of scope
Fixing baseline findings; any logic change; Compose-specific rule sets unless trivial.
### Files likely touched
`build.gradle.kts`, `gradle/libs.versions.toml`, `config/detekt/`, `README.md`. Protected paths => owner merges with `OWNER=1`.
### Log
- 2026-11-25 filed (owner approved detekt).

## T28 — Set publishing groupId to com.magicik.adlib
- Status: ready
- Type: chore · Size: s · Complex: no · Priority: p2 · Depends on: none
### Goal
Owner decided (2026-11-25): groupId = `com.magicik.adlib`. Replace `com.github.PLACEHOLDER` in the three publication blocks and remove the placeholder TODOs.
### Acceptance criteria
- [ ] `groupId = "com.magicik.adlib"` in `ads-core`, `ads-testing`, `ads-ui-compose` publication blocks (keep existing artifactIds; if they are not unique/clear, report instead of renaming). Prefer one shared constant over three copies if trivial.
- [ ] `TODO(publish)` comments removed; README "TODO publish (JitPack)" section rewritten: groupId, the three artifact coordinates, how to publish locally (`publishToMavenLocal`) and the JitPack caveat below. `CLAUDE.md` is NOT edited by Dev (protected; orchestrator/owner updates it).
- [ ] Verify with `./gradlew publishToMavenLocal` (or equivalent) that the three artifacts land under `com/magicik/adlib/` in the local Maven repo; report the paths. Do not publish anywhere real.
- [ ] README states the open question: on JitPack a GitHub repo is normally consumed as `com.github.magicik:adlib:<tag>`; whether the custom group `com.magicik.adlib` is served as-is must be confirmed on the first real JitPack build (owner check, no code change expected).
- [ ] `./scripts/check.sh` passes (ktlint included).
### Out of scope
Renaming package `com.example.adlib` (still tentative; separate decision); publishing for real; Maven Central.
### Files likely touched
`ads-*/build.gradle.kts` (protected: owner merges with `OWNER=1`), `README.md`.
### Log
- 2026-11-25 filed (owner decided groupId com.magicik.adlib).
