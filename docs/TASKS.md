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
- Already done before this board (2026-11-23): scaffold, ConfigParser + PlacementConfig, AdGateway/SdkAdGateway/MainSerialDispatcher/Clock, FakeAdGateway/FakeClock, ConsentGate/UmpConsentSource, AdsBootstrap/MobileAdsInitializer, FakeConsentSource/FakeSdkInitializer.

---

## T1 — Placement registry, config lookup, Premium, kill switch, events
- Status: ready
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

## T2 — Test mode (R22-R27)
- Status: backlog
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

## T3 — AdPool (waiter-driven single-flight, tiers, expiry)
- Status: backlog
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

## T4 — FullScreenGate + watchdog
- Status: backlog
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

## T5 — Interstitial: preload + showThen + caps
- Status: backlog
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T2, T3, T4
### Goal
`Ads.interstitial.preload(placement)` and `showThen(activity, placement) { action() }` where the action ALWAYS runs. v3 4, 6.2, 6.4, 6.6; R28-R31.
### Acceptance criteria
- [ ] `action` runs exactly once in every branch: ad shown then dismissed, no ad, load error, show error, capped, timeout, gate busy, premium, kill switch. One table-driven test.
- [ ] Host recreated during the flow: action waits until the new host is STARTED (never runs on a dead host, never dropped).
- [ ] Recheck at show (v3 6.6) via T1 `isAdAllowed` + expiry.
- [ ] Caps: dailyCap, oneInN, interval = T2 `effectiveInterstitialIntervalMs`, plus the T4 gap. Day boundary via `Clock`.
- [ ] Retry after a failed preload follows the old lib (v1 R28-R31 says 5s/15s); read the old lib to confirm the exact rule, report any difference in NOTES_FOR_OWNER.
### Out of scope
Splash (not in GD2).
### Files likely touched
`ads-core/.../core/fullscreen/interstitial/` (new), tests.
### Log

## T6 — Rewarded
- Status: backlog
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T3, T4
### Goal
`Ads.rewarded.isAvailable/isAllowed(placement): Flow<Boolean>` and `request(activity, placement, onEarned, onClosed, onUnavailable)`. v3 4, 6.2; R32 (waiting screen, timeout 10s, 3 separate callbacks).
### Acceptance criteria
- [ ] Exactly one of onUnavailable / (onEarned? then onClosed) per request in every branch (no ad, timeout 10s, show error, premium/kill switch, gate busy). Table test.
- [ ] While waiting for a load the gate is Reserved (the "waiting screen" owner); release on every exit.
- [ ] `isAllowed` reflects premium/kill switch/canRequestAds/config live; `isAvailable` reflects a non-expired pooled ad.
- [ ] `gone_in_test_mode` does NOT apply (Q-C).
- [ ] Uses `SdkAdGateway.showRewarded` (SDK 1.7.0 single load/show signature).
### Out of scope
Waiting-screen UI (app supplies it; lib exposes state).
### Files likely touched
`ads-core/.../core/fullscreen/rewarded/` (new), tests.
### Log

## T7 — App Open on resume: rules and state machine
- Status: backlog
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
### Out of scope
Overlay drawing and Back handling (T8), Splash.
### Files likely touched
`ads-core/.../core/appopen/` (new), `core/consent/ConsentGate.kt`, `ads-testing/` fakes, tests.
### Log

## T8 — App Open overlay + Back blocking
- Status: backlog
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

## T9 — ads-ui-compose: Native
- Status: backlog
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

## T10 — ads-ui-compose: Banner
- Status: backlog
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

## T11 — Ads.install facade + AdsOptions
- Status: backlog
- Type: feature · Size: m · Complex: no · Priority: p1 · Depends on: T1, T2, T3, T4, T5, T6, T7
### Goal
The public entry point of v3 section 4: `Ads.install(application, AdsOptions(...))`, main process only (L6), wiring ConsentGate, AdsBootstrap, pool, gates, events.
### Acceptance criteria
- [ ] `Ads.install` is idempotent, runs only in the main process, sync and without network (T0).
- [ ] `Ads.consent.gather(activity)` (Q-G), `canRequestAds`, `privacyOptionsRequired`, `showPrivacyOptions(activity)` delegate to ConsentGate.
- [ ] `Ads.setPremium/isPremium/setKillSwitch`, `Ads.interstitial`, `Ads.rewarded`, `Ads.appOpen` exposed as v3 section 4.
- [ ] Early validation errors from T1 surface at install.
- [ ] One integration test with all fakes: install -> consent -> init -> preload -> showThen.
### Out of scope
LegacyConfigReader (T14).
### Files likely touched
`ads-core/.../core/Ads.kt`, `AdsOptions.kt` (new), tests.
### Log

## T12 — Sample app wiring + README
- Status: backlog
- Type: chore · Size: m · Complex: no · Priority: p1 · Depends on: T8, T9, T10, T11
### Goal
`sample` uses the real API with Google test unit ids (v3 2) on every format; README documents install, consent, placements, config schema, test mode, publishing placeholder, ktlint/detekt not yet added.
### Acceptance criteria
- [ ] Sample builds (`check.sh`), screens for native grid, banner, interstitial, rewarded, app-open resume.
- [ ] README sections as above; links to v3 for design.
### Out of scope
Publishing for real (groupId still PLACEHOLDER).
### Files likely touched
`sample/`, `README.md`.
### Log

## T13 — Measure real callback threads on a device
- Status: blocked
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

## T14 — LegacyConfigReader + ColorVerse migration guide
- Status: blocked
- Type: feature · Size: m · Complex: no · Priority: p2 · Depends on: T11
### Goal
Optional reader for the old schema (enabled in AdsOptions), tested against the old `PlacementConfig.fromJson`; migration guide incl. H1 (old ColorVerse reads only `list_ads`: dual JSON or RC condition by app version). `docs/ads-lib-config-schema.txt`.
### Acceptance criteria
- [ ] Mapping table tests from the schema doc; BookReader does not enable it.
### Out of scope
Changing ColorVerse.
### Files likely touched
`ads-core/.../core/config/LegacyConfigReader.kt` (new), `docs/migrate-colorverse.md` (new).
### Log
- 2026-11-23 owner: do this only when ColorVerse migration starts.
