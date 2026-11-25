# AdLib — Android ads library on GMA Next-Gen SDK

Thu vien quang cao moi (thay lib cu `D:\Workspace\Google_Dev\BTN\Project\AdsExample\ads`), dung
`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.7.0`. App dau tien dung: BookReader
(`D:\Workspace\Google_Dev\LLC_NGHIA\BookReader`, chi DOC, khong sua tu repo nay). Owner tu ap dung lib cho app khac; lib duoc test trong app `sample` cua repo nay.
Remote: `origin` = git@github.com:magicik/adlib.git (GitHub, them 2026-11-23). KHONG dung glab / GitLab issues.

## Nguon su that (doc truoc khi lam)
- `docs/ads-lib-phase1-v3.txt` — thiet ke da chot (v3 thang moi tai lieu cu). Muc 0 = quyet dinh owner, muc 11 = cau hoi (tat ca da tra loi).
- `docs/ads-lib-config-schema.txt` — schema cu/moi + mapping (chi tham khao; LegacyConfigReader da BO).
- `docs/compose-utils-notes.md` — ghi chu doc ma nguon compose_utils Next-Gen cho native Compose.
- `docs/ARCHITECTURE.md` — ban do module.
- `docs/TASKS.md` — backlog/bang viec cho agent (T1..). `docs/agents/dev-workflow.md` — quy trinh Dev.
- `docs/history/` — v1 + v2 addendum (lich su; bang ma yeu cau R/P/H o v1 muc 2.2).

## Quyet dinh owner (tom tat; chi tiet o v3)
- Package `com.example.adlib` (tam), Compose BOM 2026.09.00, publish JitPack (groupId `com.magicik.adlib`, owner 2026-11-25; JitPack serve dung group nay hay `com.github.magicik` thi xac nhan o lan build that dau tien).
- Tu lam AdPool (khong dung Preloader), UMP truoc roi chi init SDK khi canRequestAds, consent timeout 10s (khong tinh luc form hien).
- Config cho song song voi consent. Banner load truc tiep, khong pool. Tran 8 o native/man.
- App Open khi QUAY LAI app (R33) BAT BUOC: lop phu + progress o lan dau, show ngay khi ad ve, CHAN Back trong luc lop phu (co tran overlayMaxMs), minBackgroundMs mac dinh 0, pauseForExternalFlow tu het han (60s) va chi 1 lan quay lai.
- KHONG lam Splash o GD2. KHONG lam ads-ui-view yet (ads-ui-compose truoc).
- Parser loi CHI doc schema MOI. [owner 2026-11-23] BO LegacyConfigReader va migrate ColorVerse (thang v3 muc 0/10).
- Mac dinh config: truong thieu lay tu default placement; JSON loi/rong = default; khong default = tat; `tiers.normal[].enable` thieu = TRUE (owner 2026-11-23).
- Q-A..Q-H: App Open khong tinh vao dailyCap/oneInN; total_timeout khong tinh high floor; gone_in_test_mode khong ap rewarded; lop phu chi lan dau moi process; app goi Ads.consent.gather(activity); watchdog 2 phut.

## Quy tac lam viec
- Owner chat tieng Viet. Tra loi ngan, bao cao ket qua that (test fail thi noi).
- Khong kill process cua nguoi khac. Khong sua BookReader tu day.
- `ANDROID_HOME` dat san trong `.claude/settings.local.json` (khong commit); khong can `local.properties`.
- Khong them tinh nang ngoai v3 ma khong hoi owner.

## Agent team (chuyen tu BookReader, ban local - owner 2026-11-23)
Viec nam trong `docs/TASKS.md` (KHONG dung GitHub/GitLab issues, khong can `gh`). Lam TUAN TU trong checkout nay
(khong worktree) tru khi owner bao song song. Orchestrator (phien chinh) chi giao viec, khong tu viet code.

| Role | Agent | Does | Never does |
|------|-------|------|-----------|
| Tracker | `tracker` (haiku) | Pick next, status/log in TASKS.md (on main), status report | Plans, code |
| Dev | `dev` | ONE task per branch `<type>/T<n>-<slug>`, tests, commit | Push, merge, edit TASKS.md |
| Fast Dev | `dev-fast` | Same, for Size s / chore / test | Same |
| Senior Dev | `dev-senior` (opus) | Same, for `Complex: yes` or after dev failed QA twice | Same |
| QA | `qa` | Reviews branch diff vs acceptance criteria, runs check.sh, verdict | Edits `src/main` |
| Owner | human | Answers NOTES_FOR_OWNER, merges what merge-task.sh skips, device checks | - |

- Dev procedure + report format: `docs/agents/dev-workflow.md`. QA: `.claude/agents/qa.md`.
- Merge: ONLY `./scripts/merge-task.sh <branch> <approved-sha>` (policy `.claude/automerge.conf`, agents must not edit it):
  merges `--no-ff` into main, runs check.sh on merged main (undoes the merge if it fails), pushes origin main (owner: auto-push ON).
  Protected paths (`.claude/`, `CLAUDE.md`, `scripts/`, `gradle/`, build files, manifests, proguard) => owner merges: `OWNER=1 ./scripts/merge-task.sh ...`.
- Never push to main by hand, never force-push. Commits: Conventional Commits ending with `(T<n>)`.

## Testing policy
Tests protect logic. Write the fewest tests that would catch a real regression.
| Code | Test? |
|------|-------|
| Pool, gates, consent/bootstrap, caps, app-open rules, config parsing, slot/holder logic | **Required** - JVM unit test, virtual time |
| Bug fix | **Required** - one test that reproduces the bug (fails before the fix) |
| Compose layout / visuals, overlay drawing | No test - manual check noted for owner |
| Real SDK behavior (threads, rendering, policy) | No unit test - device check (owner) |
| Data classes, trivial mappers, DI/wiring, chores | No test |
- One test per acceptance criterion; several inputs with the same behavior -> one table-driven test.
- Fakes in `ads-testing` (FakeAdGateway, FakeClock, FakeConsentSource, FakeSdkInitializer, ...); add missing ones there. MockK only for Android classes you cannot construct (Activity).
- Never `Thread.sleep`, never real time; `@Ignore` / weakened assertions are rule violations.
- New test code ~<= production code changed; if much larger, say why.

## Definition of Done (ALL must be true)
1. `./scripts/check.sh` passes (ktlintCheck + detekt + assembleDebug + testDebugUnitTest; detekt chi chan loi MOI, baseline o `config/detekt/baseline.xml`).
2. Every acceptance criterion covered per the Testing policy.
3. QA verdict `QA: APPROVED @<sha>` for that exact commit (chore/test: Dev `DEV: CHECK PASS @<sha>`).
4. No TODO without a task id. `docs/ARCHITECTURE.md` updated when structure changed.

## Limits
- Dev <-> QA: max 3 rounds per task, then `blocked` ("needs owner").
- New dependency -> version catalog + reason in the Dev report (and it touches a protected path => owner merges).
- Never touch: keystores, `local.properties`, `.env*`, `.claude/automerge.conf`. Don't bump minSdk/compileSdk/AGP/Kotlin/SDK versions unless the task says so.

## Cost rules (all agents)
- Read `docs/ARCHITECTURE.md` before exploring; `grep -n` + targeted reads; never read `build/`, `.gradle/`, `logs/` wholesale.
- While coding: `./scripts/test-quick.sh "<Pattern>" [:module]`; `./scripts/check.sh` once before handing over (max 3 runs/task). It reports all failures at once and auto-skips when no code changed.
- Non-code changes (`*.md`, `docs/`, `.claude/`) never need a build: `./scripts/changed-scope.sh [base]`.
- Existing files: `Edit` the changed lines, never `Write` the whole file again.
- Reports = the machine-readable lines the agent file asks for. Don't edit this file mid-sprint (prompt cache).

## Commands
- `/next [T<n>]` - next ready task through Dev -> QA -> merge-task.sh
- `/sprint 2` - `/next` up to 3 times; more => `./scripts/run-sprint.sh <N>` (fresh headless session per task, logs in `logs/`)
- `/bug <description>` - add a bug task; p0/p1 fixed right away
- `/status` - board summary

## Lessons learned
<!-- Owner adds one line each time an agent makes the same mistake twice. -->
- (none yet)

## Trang thai hien tai (cap nhat sau moi buoc)
_Cap nhat 2026-11-25, board T1..T28 ALL done, check.sh PASS (125 test, ktlint + detekt)._
Board `docs/TASKS.md` la nguon su that; ket qua may that Pixel 6a: `docs/sample-smoke.md` (lan 2026-11-25).
Treo / can owner:
- Device: Back bi chan trong lop phu App Open (lop phu ~1s, adb khong bat kip), tran 8 o native (dem bang mat), dailyCap tren may that (sample chua co dailyCap thap; T18 chi co JVM test), thread cua native onAdPaid/Impression va onAdFailedToShow.
- Chua quyet: NativeComposeUtility.kt (ban copy Apache-2.0) da bi ktlint format lai 354 dong, co loai khoi ktlint khong; 4 muc SwallowedException trong baseline detekt; task sample (dailyCap thap, tat validator popup); log khi App Open bi bo qua do minGap; attach App Open ca khi Premium luc install.
- Package `com.example.adlib` van tam; rename la quyet dinh rieng. Chua publish that.
- `gradle/gradle-daemon-jvm.properties` untracked tu truoc: de nguyen.
