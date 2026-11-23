---
description: File a bug as a task in docs/TASKS.md and (if p0/p1) fix it right away
argument-hint: <bug description, steps, logs>
---

Bug report from the owner:

$ARGUMENTS

1. `grep -n "^## T" docs/TASKS.md` and check for a duplicate (same symptom/area). If one exists, append the report as a `### Log` line on it (on main, commit `chore(tasks): note on T<n>`), tell me which task, and stop.
2. Otherwise append a new task at the end of `docs/TASKS.md` on main using the bug format from its Conventions section (`Type: bug`, `Status: ready`; priority: crash/hang/money lost/blocks a core flow → p0, wrong behavior with workaround → p1, cosmetic → p2; `Complex: yes` only for concurrency/lifecycle bugs with no obvious cause). Commit `chore(tasks): add T<n> <title>`.
3. If p0 or p1, run the `/next` cycle on that task id (Dev must write a failing test that reproduces the bug before fixing it). If p2, just report the task id.
