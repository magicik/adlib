#!/usr/bin/env bash
# Definition-of-Done gate: assembleDebug + testDebugUnitTest (+ ktlint/detekt once they exist).
# Agents and scripts/merge-task.sh run this.
#
# - ONE Gradle invocation, --continue: every failure is reported in one run, fix them all at once
# - Skips entirely if no CODE changed since the last successful run (docs/.claude/*.md never need a build)
# - Full output in build/check.log; the screen shows a short summary
# Env: CHECK_FORCE=1 (never skip) · CHECK_VERBOSE=1 (show Gradle output)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

LOG="build/check.log"
PASS_MARK="build/.check-pass"
mkdir -p build
START=$SECONDS

# ── 0. Skip if no CODE changed since the last successful run ────────────────
DOC_RE='(\.md$|^docs/|^\.claude/|^LICENSE|^\.gitignore$|^\.gitattributes$|^logs/)'
fingerprint() {
  {
    git ls-files -s | sed 's/^[0-9]* \([0-9a-f]*\) [0-9]\t/\1 /' | awk -v re="$DOC_RE" '{ p=$0; sub(/^[^ ]* /,"",p); if (p !~ re) print }'
    { git diff --name-only; git ls-files --others --exclude-standard; } | sort -u | grep -vE "$DOC_RE" \
      | while IFS= read -r f; do [ -f "$f" ] && echo "$(git hash-object "$f") $f"; done
  } | git hash-object --stdin
}
FP=$(fingerprint 2>/dev/null || echo none)
if [ "${CHECK_FORCE:-0}" != "1" ] && [ "$FP" != "none" ] && [ -f "$PASS_MARK" ] && [ "$(cat "$PASS_MARK")" = "$FP" ]; then
  echo "✅ check.sh skipped — no code changes since the last successful run. (CHECK_FORCE=1 to force)"
  exit 0
fi
rm -f "$PASS_MARK"
: > "$LOG"

GRADLE="./gradlew --console=plain --warning-mode=summary"
chmod +x ./gradlew 2>/dev/null || true

# ── 1. Optional style tasks (cached until build files change) ───────────────
build_files_hash=$(find . -path ./build -prune -o -path '*/build' -prune -o -path ./.claude -prune -o \
    \( -name '*.gradle.kts' -o -name 'libs.versions.toml' -o -name 'gradle.properties' \) -print 2>/dev/null \
  | sort | xargs cat 2>/dev/null | git hash-object --stdin)
TASK_CACHE="build/.check-tasks-$build_files_hash"
if [ -f "$TASK_CACHE" ]; then
  TASKS=$(cat "$TASK_CACHE")
else
  printf '▶ %-28s' "discover tasks (once)"
  rm -f build/.check-tasks-*
  TASKS=$($GRADLE tasks --all -q 2>>"$LOG" | awk '{print $1}' | sed 's/^.*://' | sort -u)
  printf '%s\n' "$TASKS" > "$TASK_CACHE"
  echo "ok"
fi
has_task() { grep -qx "$1" <<<"$TASKS"; }

CHECKS=()
has_task spotlessCheck && CHECKS+=(spotlessCheck)
has_task ktlintCheck   && CHECKS+=(ktlintCheck)
has_task detekt        && CHECKS+=(detekt)
CHECKS+=(assembleDebug testDebugUnitTest)

# ── 2. Run everything in one Gradle invocation ──────────────────────────────
echo "▶ gradle: ${CHECKS[*]}"
t0=$SECONDS
if [ "${CHECK_VERBOSE:-0}" = "1" ]; then
  $GRADLE --continue "${CHECKS[@]}" 2>&1 | tee -a "$LOG"; rc=${PIPESTATUS[0]}
else
  $GRADLE --continue "${CHECKS[@]}" >>"$LOG" 2>&1; rc=$?
fi
dt=$((SECONDS - t0))

# ── 3. Report ───────────────────────────────────────────────────────────────
if [ $rc -ne 0 ]; then
  echo "FAILED after ${dt}s"
  echo "────────────────────────────────────────"
  echo "Failed tasks:"
  grep -hE "^> Task :[^ ]+ FAILED$" "$LOG" | sed 's/^> Task /  /; s/ FAILED$//' | sort -u
  grep -hE "^[[:alnum:]_.$]+ > .+ FAILED$" "$LOG" | sort -u | head -20 | sed 's/^/  test: /'
  grep -hE "^(e: |error: |.*\.kt:[0-9]+:[0-9]+: )" "$LOG" | sort -u | head -30
  grep -n -A3 "What went wrong" "$LOG" | head -20
  echo "────────────────────────────────────────"
  echo "❌ CHECK FAIL — fix ALL of the above, then re-run (full log: $LOG)"
  exit 1
fi

total=0; failed=0
for f in $(find . -path "*/build/test-results/testDebugUnitTest/*.xml" 2>/dev/null); do
  t=$(grep -o 'tests="[0-9]*"' "$f" | head -1 | tr -dc 0-9); total=$((total + ${t:-0}))
  x=$(grep -o 'failures="[0-9]*"' "$f" | head -1 | tr -dc 0-9); failed=$((failed + ${x:-0}))
done
echo "✅ CHECK PASS in $((SECONDS - START))s — unit tests: $total run, $failed failed"
echo "$FP" > "$PASS_MARK"
