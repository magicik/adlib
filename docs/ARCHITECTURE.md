# Architecture

| Module | Package | Role |
|--------|---------|------|
| ads-core | com.example.adlib.core | SDK-independent logic + ads-mobile-sdk 1.7.0 + UMP + coroutines. No View/Compose. |
| ads-ui-compose | com.example.adlib.ui.compose | Compose native/banner helpers. Depends on ads-core. |
| ads-testing | com.example.adlib.testing | Fakes (AdGateway, consent, clock, foreground). Depends on ads-core. |
| sample | com.example.adlib.sample | Demo app. |

Design: `BookReader/docs/ads-lib-phase1-v3.txt` (sections 0, 1, 2, 10). Not copied here.
Current code:
- `core.config`: `PlacementConfig` (+ `AdTiers`, `HighFloorTier`, `AdUnitTier`, `RefreshConfig`, `PlacementDefault`) and internal `ConfigParser` (new schema, design section 3; warnings via `ConfigWarningSink`).
