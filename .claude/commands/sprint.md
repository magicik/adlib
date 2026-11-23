---
description: Run /next several times in a row
argument-hint: <number of tasks, default 2>
---

> **Cheaper alternative:** `./scripts/run-sprint.sh <N>` runs each task in a fresh session, so context never piles up. Use this command only for 1–3 tasks while you are watching.

Run the `/next` cycle (as defined in `.claude/commands/next.md`) up to $ARGUMENTS times (default 2, **max 3** in one session). If more were asked for, do 3 and tell me to use `./scripts/run-sprint.sh` for the rest.
Between cycles, keep only the one-line result of each cycle in mind — don't re-read earlier reports.

Rules:
- Each cycle starts from main (`git checkout main`; `git pull --ff-only origin main` if a remote exists).
- A task that `merge-task.sh` skipped stays in `review` until the owner merges it, so a later task may depend on it. If the tracker's pick depends on an unmerged task, skip to the next independent task; if none remain, stop.
- Stop early if any cycle ends `blocked` twice in a row — something systemic is wrong.

At the end, give me one combined table: task | branch | result | rounds, followed by the list of branches waiting for my merge, in the order I should merge them.
