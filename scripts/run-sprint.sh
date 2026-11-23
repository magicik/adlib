#!/usr/bin/env bash
# Run N work cycles, each in a FRESH headless Claude Code session.
# Cheaper than one long `/sprint N`: context never grows across tasks, and each
# session is capped by --max-turns.
#
# Usage:   ./scripts/run-sprint.sh [N=3] [MAX_TURNS=150]
# Logs:    logs/agent-<timestamp>-<i>.log
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

N="${1:-3}"
MAX_TURNS="${2:-150}"
mkdir -p logs
stamp=$(date +%Y%m%d-%H%M)
blocked_in_a_row=0

for i in $(seq 1 "$N"); do
  echo "═══ cycle $i/$N ═══"
  git checkout -q main || { echo "cannot checkout main — stopping"; exit 1; }
  if git remote get-url origin >/dev/null 2>&1; then
    git pull -q --ff-only origin main || { echo "git pull failed — stopping"; exit 1; }
  fi

  log="logs/agent-$stamp-$i.log"
  claude -p "/next" --model sonnet --max-turns "$MAX_TURNS" > "$log" 2>&1
  rc=$?

  tail -n 12 "$log"

  if grep -q "NEXT: none" "$log"; then
    echo "Nothing ready to work on — stopping."
    break
  fi
  if [ $rc -ne 0 ] || grep -qi "blocked" "$log"; then
    blocked_in_a_row=$((blocked_in_a_row + 1))
  else
    blocked_in_a_row=0
  fi
  if [ $blocked_in_a_row -ge 2 ]; then
    echo "Two cycles in a row ended blocked/failed — stopping so you can look."
    break
  fi
done

echo
echo "Done. Full logs in ./logs — run 'claude' then '/status' to see the board."
