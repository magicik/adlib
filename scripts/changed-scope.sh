#!/usr/bin/env bash
# Tell whether changes touch code or only docs / agent config.
#
# Usage:  ./scripts/changed-scope.sh [base-ref]
#   base-ref defaults to main. Pass a commit SHA to compare against an earlier point
#   (e.g. the commit QA last reviewed).
# Output (last line): "SCOPE: docs-only" or "SCOPE: code"
# Exit code: 0 = docs-only, 1 = code
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

BASE="${1:-main}"

# Non-code paths: changing only these never needs a build or tests.
DOC_RE='(\.md$|^docs/|^\.claude/|^LICENSE|^\.gitignore$|^\.gitattributes$|^logs/)'

files=$( {
  git diff --name-only "$BASE"...HEAD 2>/dev/null || git diff --name-only "$BASE" HEAD
  git diff --name-only HEAD                       # uncommitted changes
  git ls-files --others --exclude-standard        # new untracked files
} | sort -u | sed '/^$/d')

code=$(printf '%s\n' "$files" | grep -vE "$DOC_RE" | sed '/^$/d')
docs=$(printf '%s\n' "$files" | grep -E  "$DOC_RE" | sed '/^$/d')

n_code=$(printf '%s' "$code" | grep -c . || true)
n_docs=$(printf '%s' "$docs" | grep -c . || true)

if [ "$n_code" -eq 0 ]; then
  echo "changed: $n_docs non-code file(s)"
  echo "SCOPE: docs-only"
  exit 0
fi
echo "changed: $n_code code file(s), $n_docs non-code file(s)"
printf '%s\n' "$code" | head -10 | sed 's/^/  code: /'
[ "$n_code" -gt 10 ] && echo "  … and $((n_code - 10)) more"
echo "SCOPE: code"
exit 1
