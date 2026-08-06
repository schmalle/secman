#!/usr/bin/env bash
#
# test-coverage-report.sh — which production units have no test that even names them.
#
# WHAT THIS IS NOT
# ----------------
# This is not line coverage. There is no JaCoCo/Kover in this build (see
# docs/TESTING.md §Coverage), so nothing here knows which branches executed. What it
# does is cheaper and still useful: for every production class/module it asks whether
# ANY test file mentions its name. A unit no test even names is certainly untested; a
# unit that is named may still be barely tested. Read the output as a floor on the
# gap, never as a coverage percentage.
#
# It is deliberately offline — no Gradle, no database, no pass-cli, no running stack —
# so it can be run on any checkout in seconds.
#
# Usage:
#   ./scripts/test-coverage-report.sh              # summary + the untested names
#   ./scripts/test-coverage-report.sh --summary    # counts only
#   ./scripts/test-coverage-report.sh --area service   # one area (see AREAS below)
#
# Exit codes: 0 = report produced   2 = usage/environment error
# It never fails on finding gaps: this is a report, not a gate. Coverage targets
# belong in review, not in a script that would block every unrelated change.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

SUMMARY_ONLY=0
ONLY_AREA=""

while [ $# -gt 0 ]; do
    case "$1" in
        --summary) SUMMARY_ONLY=1; shift ;;
        --area)    ONLY_AREA="${2:-}"; [ -z "$ONLY_AREA" ] && { echo "--area needs a value" >&2; exit 2; }; shift 2 ;;
        -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)         echo "usage: $0 [--summary] [--area <name>]" >&2; exit 2 ;;
    esac
done

command -v grep >/dev/null || { echo "FATAL: grep not found" >&2; exit 2; }

BACKEND_MAIN="src/backendng/src/main/kotlin/com/secman"
BACKEND_TEST="src/backendng/src/test/kotlin/com/secman"
CLI_MAIN="src/cli/src/main/kotlin/com/secman/cli"
CLI_TEST="src/cli/src/test"
FRONTEND_SRC="src/frontend/src"

for d in "$BACKEND_MAIN" "$FRONTEND_SRC"; do
    [ -d "$d" ] || { echo "FATAL: $d not found (run from the repo root)" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Areas: <name>|<main dir>|<file glob>|<test corpus dir>
# Each line is one reported section. Backend domain/ and dto/ are intentionally
# excluded: they are overwhelmingly data holders whose behaviour is asserted through
# the services that use them, so listing them would bury the sections that matter.
AREAS="
controller|$BACKEND_MAIN/controller|*.kt|$BACKEND_TEST
service|$BACKEND_MAIN/service|*.kt|$BACKEND_TEST
util|$BACKEND_MAIN/util|*.kt|$BACKEND_TEST
mcp-tools|$BACKEND_MAIN/mcp/tools|*.kt|$BACKEND_TEST
cli-commands|$CLI_MAIN/commands|*.kt|$CLI_TEST
frontend-utils|$FRONTEND_SRC/utils|*.ts|$FRONTEND_SRC
frontend-services|$FRONTEND_SRC/services|*.ts|$FRONTEND_SRC
frontend-logic|$FRONTEND_SRC/components|*.ts|$FRONTEND_SRC
"

# A name counts as covered when a *test* file mentions it. Restricting the corpus to
# test files is what stops production cross-references from masking a gap.
test_files_of() {  # test_files_of <dir>
    [ -d "$1" ] || return 0
    find "$1" \( -name '*Test.kt' -o -name '*.test.ts' \) -type f 2>/dev/null
}

TOTAL_UNITS=0
TOTAL_UNCOVERED=0
REPORT="$TMP/report"
: > "$REPORT"

printf '%s\n' "test-coverage-report — name-reference coverage (NOT line coverage)"
printf '%s\n\n' "repo: $REPO_ROOT"

while IFS='|' read -r area main_dir glob test_dir; do
    [ -z "$area" ] && continue
    [ -n "$ONLY_AREA" ] && [ "$area" != "$ONLY_AREA" ] && continue

    if [ ! -d "$main_dir" ]; then
        printf '%-18s  (skipped: %s not present)\n' "$area" "$main_dir"
        continue
    fi

    test_files_of "$test_dir" > "$TMP/testfiles" || true
    if [ ! -s "$TMP/testfiles" ]; then
        : > "$TMP/corpus"
    else
        # One concatenated corpus: far faster than grepping per unit, and the only
        # thing we need from it is whether a name appears anywhere.
        xargs -a "$TMP/testfiles" cat 2>/dev/null > "$TMP/corpus" || : > "$TMP/corpus"
    fi

    units=0
    uncovered=0
    : > "$TMP/uncovered"

    while IFS= read -r f; do
        base="$(basename "$f")"
        name="${base%.*}"
        # Test files and type-only barrels are not units under test themselves.
        case "$name" in *.test|*Test) continue ;; esac
        units=$((units + 1))
        if ! grep -qw -- "$name" "$TMP/corpus" 2>/dev/null; then
            uncovered=$((uncovered + 1))
            echo "$name" >> "$TMP/uncovered"
        fi
    done < <(find "$main_dir" -maxdepth 1 -name "$glob" -type f 2>/dev/null | sort)

    TOTAL_UNITS=$((TOTAL_UNITS + units))
    TOTAL_UNCOVERED=$((TOTAL_UNCOVERED + uncovered))

    covered=$((units - uncovered))
    pct=0
    [ "$units" -gt 0 ] && pct=$(( covered * 100 / units ))
    printf '%-18s  %3d/%-3d named by a test  (%3d%%)\n' "$area" "$covered" "$units" "$pct"

    if [ "$SUMMARY_ONLY" -eq 0 ] && [ -s "$TMP/uncovered" ]; then
        {
            printf '\n--- %s: %d not named by any test ---\n' "$area" "$uncovered"
            sed 's/^/  /' "$TMP/uncovered"
        } >> "$REPORT"
    fi
done <<EOF
$AREAS
EOF

covered_total=$((TOTAL_UNITS - TOTAL_UNCOVERED))
pct_total=0
[ "$TOTAL_UNITS" -gt 0 ] && pct_total=$(( covered_total * 100 / TOTAL_UNITS ))
printf '\n%-18s  %3d/%-3d named by a test  (%3d%%)\n' "TOTAL" "$covered_total" "$TOTAL_UNITS" "$pct_total"

[ -s "$REPORT" ] && cat "$REPORT"

cat <<'EOF'

Read this as a floor, not a coverage figure, in both directions:

  * A unit counted as covered may carry one trivial assertion.
  * A unit listed as uncovered may still be exercised end-to-end. Controllers and
    MCP tools especially: the E2E gates and tests/e2e/ drive them through HTTP
    without ever naming the Kotlin class, and table-driven tests such as
    McpToolPermissionsTest assert over all 85 MCP tools without naming one.

So the controller and mcp-tools percentages understate reality, while the service
and util percentages are close to honest. Use the list to choose where to look,
then read the tests before believing them.
EOF

exit 0
