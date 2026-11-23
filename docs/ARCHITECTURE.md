# Architecture

| Module | Package | Role |
|--------|---------|------|
| ads-core | com.example.adlib.core | SDK-independent logic + ads-mobile-sdk 1.7.0 + UMP + coroutines. No View/Compose. |
| ads-ui-compose | com.example.adlib.ui.compose | Compose native/banner helpers. Depends on ads-core. |
| ads-testing | com.example.adlib.testing | Fakes (AdGateway, consent, clock, foreground). Depends on ads-core. |
| sample | com.example.adlib.sample | Demo app. |

Design: `docs/ads-lib-phase1-v3.txt` (v3 wins). History: `docs/history/`. Work board: `docs/TASKS.md`.
Current code:
- `core.config`: `PlacementConfig` (+ `AdTiers`, `HighFloorTier`, `AdUnitTier`, `RefreshConfig`, `PlacementDefault`) and internal `ConfigParser` (new schema, design section 3; warnings via `ConfigWarningSink`).
- `core.consent`: `ConsentSource` (UMP isolation; load and show of the form are separate so the 10s timeout never covers the form on screen), `UmpConsentSource`, `ConsentGate` (process-level `canRequestAds` / `privacyOptionsRequired` / `formShowing` StateFlows, single-flight `gather`, late UMP answers still refresh).
- `core.bootstrap`: `SdkInitializer` + `MobileAdsInitializer` (worker thread, app id from manifest), `AdsBootstrap` (init once on first `canRequestAds == true`, config wait 8s from T0, `awaitReadyToLoad()` = false at once without consent, 4s per waiter for init).
- ads-testing: `FakeConsentSource`, `FakeSdkInitializer` (manually driven).
- `core.placement`: `Placement`, `PlacementRegistry` (rejects duplicate names / poolKey with two formats: throws when strict, else warns and skips).
- `core.state`: `ConfigProvider` (read on every lookup), internal `AdsState` (Premium flow, kill switch, `config(placement)`, `isAdAllowed` = one gate for load and show).
- `core.events`: `AdsEvent`, `AdsEventListener`, internal `AdsEventBus` (delivers on the serial dispatcher, swallows listener exceptions).
- `core.pool`: internal `AdPool` (v3 6.1/6.3) keyed by (poolKey, format), state confined to the serial scope. Waiter-driven: min(uncovered waiters, per-format permits) loads at once; a load = `awaitReadyToLoad` -> fresh config -> high floor (preload with no waiter, not native, 30s cap, abandoned when a waiter arrives) -> normal tiers within `total_timeout_ms`; result -> oldest waiter, else pool, else `Dropped(late)`; a load with no ad answers one waiter with NoAd. Full key: a high-floor arrival evicts the oldest normal (`Dropped(capped)`). Expiry by `Clock` (app open 4h, others 55 min); Premium flushes. Defaults `PoolOptions`: maxPerKey 2, 3 loads per format (old lib).
- `core.testmode`: `RevenueStore` (app persistence), internal `TestMode`. Rule (ported from old `TestAdGuard`): test mode = a native with "Test Ad" headline was seen OR paid events were seen but no currency has a positive total; fresh install = not test mode. Totals are per currency. R27b: no paid event yet => native hidden until first paid impression (`AdsEvent.HiddenOnPurpose`). `MobileAdsInitializer(testDeviceIds)` sets RequestConfiguration before init. ads-testing: `FakeRevenueStore`.
