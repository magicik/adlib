# Dev workflow (shared by `dev-fast`, `dev`, `dev-senior`)

Read `CLAUDE.md` first: decisions, Testing policy, Definition of Done and Limits are mandatory.
Work is tracked in `docs/TASKS.md` (not GitHub/GitLab issues). You implement ONE task at a time.
Design source of truth: `docs/ads-lib-phase1-v3.txt` (read only the sections your task cites).
Requirement codes R1-R48 / P / H: `docs/history/ads-lib-phase1-v1.txt` section 2.2, `docs/history/ads-lib-phase1-v2-addendum.txt`.

## New task
1. Read your task block: `grep -n "^## T" docs/TASKS.md`, then Read only that block (offset/limit).
   If a `Depends on` task is not `Status: done`, stop: `BLOCKED: depends on T<x>`.
2. `git checkout main && git checkout -b <type>/T<n>-<slug>` (type = feat | fix | test | chore).
   Never edit `docs/TASKS.md` on your branch (the tracker and scripts own it; editing it causes conflicts).
3. **Plan before you read.** Read the task's **Files likely touched** and `docs/ARCHITECTURE.md`, then write a 3-6 line plan.
   - Files over ~300 lines: `grep -n` first, then Read with offset/limit (±40 lines).
   - Budget: about 10 file reads before editing. Much more => `BLOCKED: task too large, split suggested: <how>`.
   - Never open `build/`, generated code, or the SDK AAR (use `javap` on the cached AAR only to verify an API signature, and say so).
4. Implement the smallest change that satisfies every acceptance criterion. Out-of-scope findings go in your report.
   Library rules:
   - Public API only where the task/v3 section 4 says; everything else `internal`.
   - All mutable state is confined to the ONE `MainSerialDispatcher` (v3 6.7). Callbacks from the SDK/UMP come on unknown threads: re-post them, never run inline.
   - Never hold an `Activity` in a process-level singleton (WeakReference + lifecycle, v3 7).
   - Every wait has a timeout and ends in NoAd / callback; nothing may hang (R2). "Action always runs" paths (showThen) must be tested.
   - Time: `core.time.Clock` (one shared instance) + coroutine `delay`/virtual time. Never `Thread.sleep`, never a second Clock type.
   - No SDK type in `core` public signatures except inside `gateway/Sdk*` (AdGateway isolates the SDK).
5. Tests - follow the **Testing policy** in `CLAUDE.md`:
   - Logic -> JVM unit tests with `runTest` + virtual time; Turbine for Flows; fakes from `ads-testing` (`FakeAdGateway`, `FakeClock`, `FakeConsentSource`, `FakeSdkInitializer`, ...). MockK only for Android framework classes you cannot construct (e.g. `Activity`).
   - One test per acceptance criterion; several inputs with the same behavior -> one table-driven test.
   - Bug fix -> first a failing test that reproduces it.
   - A missing fake/builder goes into `ads-testing`, not inline in the test.
6. **While coding run only your tests:** `./scripts/test-quick.sh "<TestClassPattern>" [:module]`.
6a. **Self-check** before the full gate:
   - [ ] Every logic criterion has one named test; no "just in case" tests.
   - [ ] No inline callback execution; no Activity in singletons; every wait bounded.
   - [ ] No unrelated changes, debug logs, commented-out code, TODO without a task id.
   - [ ] Bug fix: the reproducing test failed before the fix.
6b. `./scripts/check.sh` **once**; it reports every failure in one go - fix them all, verify with `test-quick.sh`, run `check.sh` a final time.
    **Max 3 `check.sh` runs per task**; still failing => `BLOCKED: check.sh failing - <summary>`.
    Never `@Ignore`, weaken assertions, or suppress warnings to pass.
6c. New package/module/important class -> update `docs/ARCHITECTURE.md` (short).
7. Commit (Conventional Commits with the task id): `feat(pool): waiter-driven single-flight loads (T3)`.
   Do NOT push and do NOT merge - the orchestrator runs `scripts/merge-task.sh`.
8. Leave the checkout on your branch (QA reviews it there).

## QA feedback
1. Read every numbered point the orchestrator passes you (QA verdict text).
2. `git checkout <your branch>`, fix each point, add/adjust tests so it cannot regress.
3. `./scripts/changed-scope.sh <sha QA reviewed>`: docs-only -> skip tests. Otherwise `test-quick.sh` for affected tests, then `check.sh` once.
4. Commit `fix: address QA round <k> (T<n>)`. Answer each point in your report.

## Conflicts with main
`git merge main` on the branch. Only non-code files conflicted -> commit, no build. Code conflicted -> `test-quick.sh` affected tests, then `check.sh` once.
Report `CONFLICTS: docs-only` or `CONFLICTS: code (<files>)`.

## Report (last lines, exactly)
```
DEV_RESULT: T<n> branch <branch> @<full head sha> | BLOCKED: <reason>
CHECK: pass | fail - <summary>
CRITERIA:
- [x] <criterion> — <TestClass.method> | manual: <why>
NOTES_FOR_OWNER: <out-of-scope findings, open design questions, or none>
```
For `Type: chore` / `Type: test` tasks (no QA) the first line is
`DEV: CHECK PASS @<full head sha>` - only if `check.sh` really passed on that exact commit.

## Editing files (output tokens are the most expensive)
- Existing file -> `Edit` the smallest unique region; never `Write` an existing file. `Write` only new files.
- Don't restate code in your report.

## Hard rules
- Never push, never merge, never force anything, never edit `.claude/automerge.conf`, `local.properties`, keystores.
- Don't change SDK/AGP/Kotlin/compileSdk versions or add dependencies unless the task says so (new dependency => version catalog + reason in report).
- Never touch BookReader. Never kill processes you did not start.
- Stuck after a genuine attempt (unclear spec, needs a real device)? Stop: `BLOCKED: <what you tried>`.
