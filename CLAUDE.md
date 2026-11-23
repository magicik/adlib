# AdLib — Android ads library on GMA Next-Gen SDK

Thu vien quang cao moi (thay lib cu `D:\Workspace\Google_Dev\BTN\Project\AdsExample\ads`), dung
`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.7.0`. App dau tien dung: BookReader
(`D:\Workspace\Google_Dev\LLC_NGHIA\BookReader`, chi DOC, khong sua tu repo nay). ColorVerse migrate sau.
Remote: `origin` = git@github.com:magicik/adlib.git (GitHub, them 2026-11-23). KHONG dung glab / GitLab issues.

## Nguon su that (doc truoc khi lam)
- `docs/ads-lib-phase1-v3.txt` — thiet ke da chot (v3 thang moi tai lieu cu). Muc 0 = quyet dinh owner, muc 11 = cau hoi (tat ca da tra loi).
- `docs/ads-lib-config-schema.txt` — schema cu/moi + mapping (cho LegacyConfigReader khi migrate ColorVerse).
- `docs/compose-utils-notes.md` — ghi chu doc ma nguon compose_utils Next-Gen cho native Compose.
- `docs/ARCHITECTURE.md` — ban do module.

## Quyet dinh owner (tom tat; chi tiet o v3)
- Package `com.example.adlib` (tam), Compose BOM 2026.09.00, publish JitPack (groupId con PLACEHOLDER).
- Tu lam AdPool (khong dung Preloader), UMP truoc roi chi init SDK khi canRequestAds, consent timeout 10s (khong tinh luc form hien).
- Config cho song song voi consent. Banner load truc tiep, khong pool. Tran 8 o native/man.
- App Open khi QUAY LAI app (R33) BAT BUOC: lop phu + progress o lan dau, show ngay khi ad ve, CHAN Back trong luc lop phu (co tran overlayMaxMs), minBackgroundMs mac dinh 0, pauseForExternalFlow tu het han (60s) va chi 1 lan quay lai.
- KHONG lam Splash o GD2. KHONG lam ads-ui-view yet (ads-ui-compose truoc).
- Parser loi CHI doc schema MOI; schema cu = LegacyConfigReader tuy chon, lam khi migrate ColorVerse (luu y: app ColorVerse cu chi doc `list_ads`, ghi JSON kep hoac RC condition theo version).
- Mac dinh config: truong thieu lay tu default placement; JSON loi/rong = default; khong default = tat; `tiers.normal[].enable` thieu = TRUE (owner 2026-11-23).
- Q-A..Q-H: App Open khong tinh vao dailyCap/oneInN; total_timeout khong tinh high floor; gone_in_test_mode khong ap rewarded; lop phu chi lan dau moi process; app goi Ads.consent.gather(activity); watchdog 2 phut.

## Quy tac lam viec
- Owner chat tieng Viet. Tra loi ngan, bao cao ket qua that (test fail thi noi).
- Giao viec cho agent (dev / dev-senior / qa; khong cho agent tu merge main); orchestrator merge. Song song => moi agent 1 git worktree + nhanh `feat/<ten>`, chi sua file trong pham vi.
- Dat `ANDROID_HOME=C:/Users/PC/AppData/Local/Android/Sdk` khi chay Gradle; `local.properties` rieng moi worktree, khong commit.
- Khong kill process cua nguoi khac. Khong sua BookReader tu day.
- `./scripts/check.sh` (assembleDebug + testDebugUnitTest) phai PASS truoc khi merge; doc build/check.log khi loi.
- Commit Conventional Commits. Tests: it nhat co y nghia, bang cho nhieu input; Fake trong `ads-testing` thay vi MockK khi co the.
- Khong them tinh nang ngoai v3 ma khong hoi owner.

## Trang thai hien tai (cap nhat sau moi buoc)
_Cap nhat 2026-11-23, main = 6c2df48 (merge consent-bootstrap), check.sh PASS._
Xong: scaffold (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, UMP 4.0.0), ConfigParser + PlacementConfig (+test bang),
AdGateway/SdkAdGateway/MainSerialDispatcher/Clock, FakeAdGateway/FakeClock (ads-testing), sample thread probe (chua do), compose-utils-notes,
ConsentGate/UmpConsentSource + AdsBootstrap/MobileAdsInitializer (+FakeConsentSource/FakeSdkInitializer, 13 test).
Chua lam (thu tu de xuat): Pool (goi bootstrap.awaitReadyToLoad() truoc moi load) (theo so nguoi cho, process scope) -> FullScreenGate/Interstitial/Rewarded (watchdog, showThen luon chay) ->
AppOpenResume -> ads-ui-compose (chep NativeComposeUtility.kt Next-Gen, giu Apache-2.0) -> Ads.install + noi ConfigWarningSink vao listener -> README -> huong dan migrate ColorVerse (+LegacyConfigReader).
Treo / can lam:
- DO THREAD THUC TE chua lam duoc: `adb devices` rong, co 4 AVD nhung thieu `Sdk/emulator/emulator.exe`. Can cam may that hoac cai emulator, cai `sample`, loc logcat tag `AdThreadProbe`, roi chot muc 6.7 cua v3.
- `core.time.Clock` do agent gateway tao; thong nhat khi viet Pool/Consent (dung chung, dung tao Clock thu hai).
- SDK 1.7.0 Rewarded chi co 1 chu ky load/show (v3 da sua).
- Worktree thua `../AdLib-wt-gateway` (va thu muc `../AdLib-wt-config`) bi loi "Filename too long": owner xoa tay.
- ktlint/detekt chua them (ghi o README).
