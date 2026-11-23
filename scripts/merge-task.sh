#!/usr/bin/env bash
# Merge ONE approved task branch into main, verify, push. The orchestrator calls this;
# the policy lives in .claude/automerge.conf (agents must not edit it).
#
# Usage:  ./scripts/merge-task.sh <branch> <approved-sha>
#   <approved-sha> = the commit QA approved (or Dev's `DEV: CHECK PASS @<sha>` for chore/test).
#   If the branch moved since then, nothing is merged.
# Owner override for protected paths / AUTO_MERGE=off:  OWNER=1 ./scripts/merge-task.sh ...
# Last line: "MERGE: merged <sha> (pushed|not pushed)"  or  "MERGE: skipped - <reason>"
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

BRANCH="${1:?usage: merge-task.sh <branch> <approved-sha>}"
APPROVED="${2:?usage: merge-task.sh <branch> <approved-sha>}"
# shellcheck disable=SC1091
source .claude/automerge.conf
skip() { echo "MERGE: skipped - $*"; exit 1; }

[ "${AUTO_MERGE:-off}" = "on" ] || [ "${OWNER:-0}" = "1" ] || skip "AUTO_MERGE=off in .claude/automerge.conf"
git rev-parse --verify -q "$BRANCH" >/dev/null || skip "branch $BRANCH not found"
[ -z "$(git status --porcelain --untracked-files=no)" ] || skip "working tree has uncommitted changes"

head=$(git rev-parse "$BRANCH")
case "$head" in
  "$APPROVED"*) ;;
  *) skip "branch head $(git rev-parse --short "$BRANCH") is not the approved commit $APPROVED (re-review needed)";;
esac

git checkout -q main || skip "cannot checkout main"
if git remote get-url origin >/dev/null 2>&1; then
  git pull -q --ff-only origin main || skip "main cannot fast-forward from origin (owner: resolve)"
fi

changed=$(git diff --name-only main..."$BRANCH")
[ -n "$changed" ] || skip "branch has no changes against main"
protected=$(printf '%s\n' "$changed" | grep -E "$PROTECTED_PATHS_RE" || true)
if [ -n "$protected" ] && [ "${OWNER:-0}" != "1" ]; then
  skip "protected paths changed (owner merges: OWNER=1 $0 $BRANCH $APPROVED): $(echo $protected)"
fi

before=$(git rev-parse HEAD)
if ! git merge -q --no-ff "$BRANCH" -m "Merge branch '$BRANCH'"; then
  git merge --abort 2>/dev/null
  skip "merge conflict with main (Dev: git merge main on the branch)"
fi

echo "▶ check.sh on merged main"
if ! ./scripts/check.sh; then
  git reset -q --hard "$before"
  skip "check.sh failed on merged main (merge undone)"
fi

merged=$(git rev-parse --short HEAD)
git branch -q -d "$BRANCH" 2>/dev/null || true

if [ "${AUTO_PUSH:-off}" = "on" ] && git remote get-url origin >/dev/null 2>&1; then
  if git push -q origin main; then
    echo "MERGE: merged $merged (pushed)"
  else
    echo "MERGE: merged $merged (not pushed - push failed, owner: git push origin main)"
  fi
else
  echo "MERGE: merged $merged (not pushed)"
fi
