#!/usr/bin/env bash
# Run only the unit tests matching a pattern, with a one-line summary.
# Use while coding instead of the full ./scripts/check.sh.
#
# Usage:  ./scripts/test-quick.sh "<TestClassPattern>" [gradle-module]
#   e.g.  ./scripts/test-quick.sh "*ConsentGateTest*"
#         ./scripts/test-quick.sh "*AdPool*" :ads-core
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PATTERN="${1:?usage: test-quick.sh \"<TestClassPattern>\" [module]}"
MODULE="${2:-}"
TASK="${MODULE:+$MODULE:}testDebugUnitTest"
LOG="build/test-quick.log"
STAMP="build/.test-quick-start"
mkdir -p build
touch "$STAMP"

./gradlew --console=plain --warning-mode=none "$TASK" --rerun --tests "$PATTERN" >"$LOG" 2>&1
rc=$?

# Count from the JUnit XML written by THIS run only.
total=0; failed=0
while IFS= read -r f; do
  t=$(grep -o 'tests="[0-9]*"' "$f" | head -1 | tr -dc 0-9); total=$((total + ${t:-0}))
  x=$(grep -o 'failures="[0-9]*"' "$f" | head -1 | tr -dc 0-9); failed=$((failed + ${x:-0}))
  e=$(grep -o 'errors="[0-9]*"' "$f" | head -1 | tr -dc 0-9); failed=$((failed + ${e:-0}))
done < <(find . -path "*/build/test-results/testDebugUnitTest/*.xml" -newer "$STAMP" 2>/dev/null)

if [ $rc -eq 0 ]; then
  if [ "$total" -eq 0 ]; then
    echo "⚠️  no test matched '$PATTERN' — check the class name"
    exit 1
  fi
  echo "✅ tests passed: $PATTERN ($total run)"
  exit 0
fi

echo "❌ tests failed: $PATTERN ($failed of $total failed)"
grep -hE " > .+ FAILED$" "$LOG" | sort -u | head -20
grep -hE "^\s+(java\.|kotlin\.|org\.junit|org\.opentest4j|io\.mockk|app\.cash\.turbine|AssertionError|Expected|expected:)" "$LOG" | head -20
grep -hE "^e: .+\.kt:" "$LOG" | sort -u | head -20          # compile errors
echo "(full log: $LOG)"
exit 1
