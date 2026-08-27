#!/usr/bin/env bash
#
# humanizer-scan.sh — mechanical half of the /humanizer code-hygiene pass.
#
# The hygiene rules this repo wants are simple to state and tedious to check:
# a file stays under 1000 lines, a function fits on a screen, every public
# declaration carries a comment that says why it exists, and names mean
# something to a human. Stating them in prose gets them partially followed.
# This script turns the checkable half into a red or green exit code.
#
# It deliberately does NOT judge comment *quality* or name *aptness* — no grep
# can tell a crisp "why" comment from one that restates the next line, and no
# regex knows whether `resolveOwner` is a better name than `getOwner`. Those
# live in `.claude/skills/humanizer/` and stay with the agent reading the diff.
#
# ---------------------------------------------------------------------------
# Scope model — why the default is diff-scoped
# ---------------------------------------------------------------------------
#
# Run over the whole repo this reports thousands of findings: 7 Kotlin files
# already exceed 1000 lines and the spec-kit `Feature: NNN-` marker is fossil-
# ized into KDoc all over the backend. A gate that is red on arrival is a gate
# everyone learns to ignore. So the default scope is what THIS change touched,
# and severity follows one principle:
#
#   the declaration that owns the finding is NEW in this change -> BLOCK
#   the declaration predates the change (you only touched near it) -> REVIEW
#
# "Declaration" means the function signature, the type header, the variable
# binding, or — for a whole-file finding — the file itself. So writing a
# 140-line function blocks; editing one line inside a 140-line function you
# inherited does not.
#
# `--all` scans every tracked source file and is for audits, not for gating.
#
# Exit codes:  0 = no BLOCK findings   1 = BLOCK findings   2 = usage/env error
#
# Usage:
#   ./scripts/humanizer-scan.sh                    # this change vs origin/main
#   ./scripts/humanizer-scan.sh --base main
#   ./scripts/humanizer-scan.sh --all              # whole-repo audit
#   ./scripts/humanizer-scan.sh src/relay          # audit a subtree
#   ./scripts/humanizer-scan.sh --verbose          # show the offending source line
#   ./scripts/humanizer-scan.sh --strict           # REVIEW findings also exit 1
#   ./scripts/humanizer-scan.sh --exclude-tests    # skip *Test.kt / *.test.ts / _test.go
#   ./scripts/humanizer-scan.sh --max-file 800 --max-func 80 --warn-func 40

set -uo pipefail

MODE="diff"
BASE="origin/main"
VERBOSE=0
STRICT=0
EXCLUDE_TESTS=0
MAX_FILE=1000
MAX_FUNC=100
WARN_FUNC=50
declare -a TARGETS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --all)            MODE="all" ;;
        --diff)           MODE="diff" ;;
        --verbose)        VERBOSE=1 ;;
        --strict)         STRICT=1 ;;
        --exclude-tests)  EXCLUDE_TESTS=1 ;;
        --base)     shift; [ $# -gt 0 ] || { echo "--base needs a ref" >&2; exit 2; }; BASE="$1" ;;
        --max-file) shift; [ $# -gt 0 ] || { echo "--max-file needs a number" >&2; exit 2; }; MAX_FILE="$1" ;;
        --max-func) shift; [ $# -gt 0 ] || { echo "--max-func needs a number" >&2; exit 2; }; MAX_FUNC="$1" ;;
        --warn-func) shift; [ $# -gt 0 ] || { echo "--warn-func needs a number" >&2; exit 2; }; WARN_FUNC="$1" ;;
        -h|--help)  sed -n '3,45p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)         echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
        *)          TARGETS+=("$1"); MODE="paths" ;;
    esac
    shift
done

case "$MAX_FILE$MAX_FUNC$WARN_FUNC" in
    *[!0-9]*) echo "line limits must be numbers" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2
git rev-parse --git-dir >/dev/null 2>&1 || { echo "FATAL: not a git repository" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- File selection ----------------------------------------------------------
#
# Extensions are the ones this repo actually ships. Anything else (yaml, sql,
# md) has no function/class structure to measure, so including it would only
# produce noise.

is_source() {
    case "$1" in
        *.kt|*.java|*.ts|*.tsx|*.js|*.jsx|*.astro|*.go|*.py|*.sh) return 0 ;;
        *) return 1 ;;
    esac
}

# Build output and vendored trees are nobody's code to fix, and the two skill
# trees are prose. Scanning them would bury real findings under generated ones.
is_excluded() {
    case "$1" in
        */node_modules/*|node_modules/*) return 0 ;;
        */build/*|build/*|*/dist/*|dist/*|*/.gradle/*) return 0 ;;
        */.venv/*|*/venv/*|*/__pycache__/*) return 0 ;;
        *.min.js|*.d.ts|*/generated/*) return 0 ;;
        .claude/*|.agents/*) return 0 ;;
    esac
    if [ "$EXCLUDE_TESTS" -eq 1 ]; then
        case "$1" in
            *Test.kt|*Tests.kt|*.test.ts|*.test.tsx|*_test.go|*/tests/*|*/test/*) return 0 ;;
        esac
    fi
    return 1
}

# Emits candidate paths on stdout; filtering to real source files happens in the
# caller so each mode here stays a one-liner about WHICH files, not which kinds.
collect_files() {
    case "$MODE" in
        all)
            git ls-files
            ;;
        paths)
            for t in "${TARGETS[@]}"; do
                [ -e "$t" ] || { echo "FATAL: no such path: $t" >&2; exit 2; }
                if [ -d "$t" ]; then git ls-files -- "$t"; else printf '%s\n' "$t"; fi
            done
            ;;
        diff)
            # Merge-base so a stale local branch does not report the whole
            # upstream delta as "yours". Untracked files count as fully new.
            local base_ref="$BASE"
            git rev-parse --verify --quiet "$base_ref" >/dev/null || base_ref="main"
            git rev-parse --verify --quiet "$base_ref" >/dev/null || base_ref=""
            if [ -n "$base_ref" ]; then
                git diff --name-only --diff-filter=ACMR "$(git merge-base HEAD "$base_ref" 2>/dev/null || echo HEAD)" 2>/dev/null
            fi
            git diff --name-only --diff-filter=ACMR HEAD 2>/dev/null
            git ls-files --others --exclude-standard
            ;;
    esac
}

collect_files | sort -u | while IFS= read -r f; do
    [ -n "$f" ] || continue
    [ -f "$f" ] || continue
    is_source "$f" || continue
    is_excluded "$f" && continue
    printf '%s\n' "$f"
done > "$TMP/files.txt"

FILE_COUNT=$(wc -l < "$TMP/files.txt" | tr -d ' ')
if [ "$FILE_COUNT" -eq 0 ]; then
    echo "humanizer-scan: no source files in scope (mode=$MODE) — nothing to check"
    exit 0
fi

# --- Which lines are new -----------------------------------------------------
#
# One "<path>\t<lineno>" per added line. In --all / path mode the set is empty,
# so every finding lands as REVIEW: an audit reports, it does not accuse.

: > "$TMP/added.tsv"
if [ "$MODE" = "diff" ]; then
    base_ref="$BASE"
    git rev-parse --verify --quiet "$base_ref" >/dev/null || base_ref="main"
    merge_base=""
    git rev-parse --verify --quiet "$base_ref" >/dev/null && \
        merge_base="$(git merge-base HEAD "$base_ref" 2>/dev/null || true)"

    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
            { [ -n "$merge_base" ] && git diff -U0 "$merge_base" -- "$f" 2>/dev/null
              git diff -U0 HEAD -- "$f" 2>/dev/null; } \
            | awk -v path="$f" '
                /^@@/ {
                    # @@ -a,b +c,d @@  -> lines c .. c+d-1 are new
                    match($0, /\+[0-9]+(,[0-9]+)?/)
                    spec = substr($0, RSTART + 1, RLENGTH - 1)
                    n = split(spec, p, ",")
                    start = p[1] + 0
                    count = (n > 1 ? p[2] + 0 : 1)
                    for (i = 0; i < count; i++) printf "%s\t%d\n", path, start + i
                }'
        else
            # Untracked: the whole file is new.
            awk -v path="$f" '{ printf "%s\t%d\n", path, NR }' "$f"
        fi
    done < "$TMP/files.txt" | sort -u > "$TMP/added.tsv"

    # A file is "new" (rather than merely edited) when git says it was added.
    { [ -n "$merge_base" ] && git diff --name-only --diff-filter=A "$merge_base" 2>/dev/null
      git ls-files --others --exclude-standard; } | sort -u > "$TMP/newfiles.txt"
else
    : > "$TMP/newfiles.txt"
fi

# --- Analysis ----------------------------------------------------------------

RULES_AWK="$REPO_ROOT/scripts/lib/humanizer-rules.awk"
[ -f "$RULES_AWK" ] || { echo "FATAL: missing $RULES_AWK" >&2; exit 2; }

# Everything C-family shares one rule set, so unknown extensions fall through to
# `ts` rather than being skipped: a wrong-but-close dialect still measures braces.
lang_of() {
    case "$1" in
        *.kt)                 echo kt ;;
        *.java)               echo java ;;
        *.go)                 echo go ;;
        *.py)                 echo py ;;
        *.sh)                 echo sh ;;
        *)                    echo ts ;;
    esac
}

: > "$TMP/findings.tsv"

while IFS= read -r f; do
    [ -n "$f" ] || continue
    awk -v path="$f" -v lang="$(lang_of "$f")" \
        -v maxfile="$MAX_FILE" -v maxfunc="$MAX_FUNC" -v warnfunc="$WARN_FUNC" \
        -f "$RULES_AWK" "$f" \
    | while IFS=$'\t' read -r rule line msg; do
        printf '%s\t%s\t%s\t%s\n' "$f" "$rule" "$line" "$msg"
      done >> "$TMP/findings.tsv"

    # Emoji in source is checked here rather than in awk: matching the 4-byte
    # emoji plane is a byte-level job and awk's locale handling for it is not
    # portable between macOS and Linux.
    case "$f" in
        *.sh|*.py) cmt='^[[:space:]]*#' ;;
        *)         cmt='^[[:space:]]*(//|\*|/\*)' ;;
    esac
    LC_ALL=C grep -n $'\xf0\x9f' "$f" 2>/dev/null | head -5 | while IFS=: read -r line rest; do
        # Emoji in a CLI `println` is a deliberate interface choice; only emoji
        # a maintainer has to read past — the ones in comments — are a finding.
        printf '%s' "$rest" | grep -Eq "$cmt" || continue
        printf '%s\tCOMMENT-SCAFFOLD\t%s\t%s\n' "$f" "$line" "emoji in a comment — decoration that a maintainer has to read past" >> "$TMP/findings.tsv"
    done
done < "$TMP/files.txt"

# --- Severity ----------------------------------------------------------------
#
# BLOCK when the change introduced the declaration the finding hangs off,
# REVIEW when it was already there. FILE-LONG asks a different question — was
# the whole FILE added — because growing an inherited 1400-line file by three
# lines is not the same offence as committing a new one.

: > "$TMP/block.txt"
: > "$TMP/review.txt"

while IFS=$'\t' read -r f rule line msg; do
    [ -n "$f" ] || continue
    sev="REVIEW"
    if [ "$rule" = "FILE-LONG" ]; then
        grep -qxF "$f" "$TMP/newfiles.txt" 2>/dev/null && sev="BLOCK"
    else
        grep -qxF "$(printf '%s\t%s' "$f" "$line")" "$TMP/added.tsv" 2>/dev/null && sev="BLOCK"
    fi
    entry="$f:$line  [$rule] $msg"
    if [ "$VERBOSE" -eq 1 ]; then
        src=$(sed -n "${line}p" "$f" 2>/dev/null | sed 's/^[[:space:]]*//' | cut -c1-110)
        [ -n "$src" ] && entry="$entry"$'\n'"        > $src"
    fi
    if [ "$sev" = "BLOCK" ]; then printf '%s\n' "$entry" >> "$TMP/block.txt"
    else printf '%s\n' "$entry" >> "$TMP/review.txt"; fi
done < "$TMP/findings.tsv"

# --- Report ------------------------------------------------------------------

# grep -c exits 1 on no match, so no `|| echo 0` here: that appended a second
# zero and every later numeric test broke on "0\n0".
BLOCK_N=$(grep -c '\[' "$TMP/block.txt" 2>/dev/null); BLOCK_N=${BLOCK_N:-0}
REVIEW_N=$(grep -c '\[' "$TMP/review.txt" 2>/dev/null); REVIEW_N=${REVIEW_N:-0}

echo "humanizer-scan: mode=$MODE  files=$FILE_COUNT  limits: file<=$MAX_FILE func<=$MAX_FUNC (target $WARN_FUNC)"

if [ "$BLOCK_N" -gt 0 ]; then
    echo
    echo "BLOCK ($BLOCK_N) — introduced by this change, fix before calling it done:"
    sed 's/^/  /' "$TMP/block.txt"
fi

if [ "$REVIEW_N" -gt 0 ]; then
    echo
    echo "REVIEW ($REVIEW_N) — pre-existing in files in scope; decide and say so, do not silently skip:"
    sed 's/^/  /' "$TMP/review.txt"
fi

if [ "$BLOCK_N" -eq 0 ] && [ "$REVIEW_N" -eq 0 ]; then
    echo "humanizer-scan: OK — no findings"
    exit 0
fi

echo
cat <<'EOF'
Rules: FILE-LONG / FUNC-MAX / FUNC-LONG are structural. UNDOC-DECL means a
declaration has no comment at all — it says nothing about whether the comments
that DO exist are any good. NAME-VAGUE and COMMENT-SCAFFOLD are candidates,
not verdicts. Judgment for all of them lives in .claude/skills/humanizer/.
EOF

if [ "$BLOCK_N" -gt 0 ]; then exit 1; fi
if [ "$STRICT" -eq 1 ] && [ "$REVIEW_N" -gt 0 ]; then exit 1; fi
exit 0
