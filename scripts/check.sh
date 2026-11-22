#!/usr/bin/env bash
# Builds debug and runs unit tests; full output goes to build/check.log, one summary line to stdout.
cd "$(dirname "$0")/.." || exit 1
mkdir -p build
if ./gradlew assembleDebug testDebugUnitTest > build/check.log 2>&1; then
  echo "CHECK PASS"
else
  echo "CHECK FAIL - see build/check.log"
  grep -n -E "^e: |FAILED|What went wrong" -A3 build/check.log | head -30
  exit 1
fi
