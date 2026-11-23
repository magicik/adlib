---
description: Take the next ready task from docs/TASKS.md through Dev → QA (max 3 rounds) → merge-task.sh
argument-hint: "[task id, e.g. T3 — optional, otherwise the tracker picks]"
---

Run one work cycle. You are the orchestrator: you only delegate to subagents and pass their short results along. Do not write code, do not read source files, and do not re-run checks yourself — that would spend tokens twice.
Work is sequential in this checkout (no worktrees, no parallel agents) unless the owner says otherwise.

1. **Pick.** If a task id was given ($ARGUMENTS), ask the `tracker` to set it `in-progress`. Otherwise ask the `tracker` for its **Pick next** job. If the result is `NEXT: none`, report why and stop.

2. **Build.** Route by the task's fields: `Complex: yes` → `dev-senior`; `Size: s`, `Type: chore` or `Type: test` → `dev-fast`; anything else → `dev`. Pass only the task id.
   **Escalation ladder:** `dev-fast` gets `CHANGES REQUESTED` once → hand the branch to `dev`. `dev` gets `CHANGES REQUESTED` twice, or returns `BLOCKED` because the problem is hard (not a missing spec or a device) → hand it to `dev-senior`, telling it to read the branch history and QA notes first.
   - If Dev returns `BLOCKED`, ask the `tracker` to set `blocked` with the reason as the log line, and stop.

3. **Verify.** `Type: chore` / `Type: test` skip QA: Dev must have reported `DEV: CHECK PASS @<sha>`; go to step 4b (`Rounds: 0 (no QA)`).
   Otherwise ask `qa` to verify task T<n> on branch `<branch>` at `<sha>` (from DEV_RESULT). For round 2+, pass QA its previous verdict.

4. **Loop.** On `CHANGES REQUESTED`, send the verdict text to the agent that built it (or the next one per the ladder) as "address QA round k", then verify again with QA (round k+1, with the previous verdict).
   Stop after 3 QA rounds: ask the `tracker` to set `blocked` with "Blocked after 3 QA rounds — needs owner", stop.

4b. **Merge.** On `QA: APPROVED @<sha>` (or the chore/test `DEV: CHECK PASS @<sha>`), run `./scripts/merge-task.sh <branch> <sha>` and keep its last line. Never merge or push yourself; the script applies `.claude/automerge.conf` (merge, check.sh on merged main, push).
   - `MERGE: merged …` → ask the `tracker` to set `done` with log line `merged <sha>; QA rounds <k>; owner-check:<none|needed>`.
   - `MERGE: skipped - <reason>` → ask the `tracker` to set `review` with log line `waiting for owner merge: <reason>`. That's fine: the owner merges.

5. **Follow-ups.** If Dev reported NOTES_FOR_OWNER or QA reported BUGS_FOUND, do not create code tasks for new features (owner approves scope). Bugs: append ONE new task per bug to `docs/TASKS.md` on main yourself (Conventions section format, `Status: ready`, `Type: bug`) and commit `chore(tasks): add T<n> <title>`. Max 3 per cycle.

6. **Report to me** in this format:
```
Task:    T<n> <title>
Branch:  <branch> @<short sha>
Result:  ✅ merged & pushed | 🟡 approved, waiting for owner merge: <reason> | ⛔ blocked: <reason>
Rounds:  <k>   Built by: <dev-fast|dev|dev-senior>
Manual check for owner: <from QA, or none>
Notes for owner: <from Dev, or none>
New tasks filed: <list or none>
```
