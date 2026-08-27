#!/usr/bin/env bash
#
# optimizer-scan.sh — mechanical half of the /optimizer pass.
#
# Two defects, one gate, because in this repo they are usually the same
# defect. The slow query that was copied into eight services is one problem
# reported twice: fix it once and eight call sites get faster, fix it eight
# times and the ninth copy is already being written.
#
#   Hot-path rules   patterns this codebase has actually been slow with —
#                    findAll() filtered in memory, a repository call inside a
#                    loop, a transaction held across an HTTP or SMTP call, a
#                    serial chain of awaits, a timer with no teardown.
#                    Every one is a finding in
#                    docs/SOURCE_REVIEW_COMPLEXITY_SPEED.md.
#
#   Clone detection  blocks of code that appear in more than one place, found
#                    by hashing sliding windows of normalized lines. Catches
#                    the fork that drifted (Export.tsx vs ImportExport.tsx)
#                    and the modal pasted twice inside one file.
#
# What it deliberately does NOT do is decide anything. It cannot tell a hot
# path from a startup path, cannot know that a 200-row table will never be
# 200,000, and cannot tell deliberate duplication (two things that merely
# resemble each other today) from a fork that will drift. Those judgments live
# in .claude/skills/optimizer/ and stay with the agent reading the diff.
#
# ---------------------------------------------------------------------------
# Scope model — why the default is diff-scoped
# ---------------------------------------------------------------------------
#
# Whole-repo this reports hundreds of findings, most of them years old and
# load-bearing. A gate that is red on arrival is a gate everyone learns to
# ignore, so the default scope is what THIS change touched:
#
#   the line that owns the finding is NEW in this change  -> BLOCK
#   it was already there and you worked nearby            -> REVIEW
#
# Clone detection reads differently, and on purpose: the CORPUS is always the
# whole repo even when the SCOPE is one file. Pasting an existing block into a
# new file has to be findable, and it is invisible if the detector only ever
# compares changed files with each other.
#
# Exit codes:  0 = no BLOCK findings   1 = BLOCK findings   2 = usage/env error
#
# Usage:
#   ./scripts/optimizer-scan.sh                     # this change vs origin/main
#   ./scripts/optimizer-scan.sh --all               # whole-repo audit
#   ./scripts/optimizer-scan.sh src/relay           # audit a subtree
#   ./scripts/optimizer-scan.sh --verbose           # show the offending source line
#   ./scripts/optimizer-scan.sh --strict            # REVIEW findings also exit 1
#   ./scripts/optimizer-scan.sh --no-clones         # hot-path rules only (fast)
#   ./scripts/optimizer-scan.sh --only-clones
#   ./scripts/optimizer-scan.sh --min-clone 20 --window 8

set -uo pipefail

MODE="diff"
BASE="origin/main"
VERBOSE=0
STRICT=0
EXCLUDE_TESTS=0
DO_RULES=1
DO_CLONES=1
MIN_CLONE=12
WINDOW=6
declare -a TARGETS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --all)            MODE="all" ;;
        --diff)           MODE="diff" ;;
        --verbose)        VERBOSE=1 ;;
        --strict)         STRICT=1 ;;
        --exclude-tests)  EXCLUDE_TESTS=1 ;;
        --no-clones)      DO_CLONES=0 ;;
        --only-clones)    DO_RULES=0 ;;
        --base)       shift; [ $# -gt 0 ] || { echo "--base needs a ref" >&2; exit 2; }; BASE="$1" ;;
        --min-clone)  shift; [ $# -gt 0 ] || { echo "--min-clone needs a number" >&2; exit 2; }; MIN_CLONE="$1" ;;
        --window)     shift; [ $# -gt 0 ] || { echo "--window needs a number" >&2; exit 2; }; WINDOW="$1" ;;
        -h|--help)  sed -n '3,56p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)         echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
        *)          TARGETS+=("$1"); MODE="paths" ;;
    esac
    shift
done

case "$MIN_CLONE$WINDOW" in
    *[!0-9]*) echo "--min-clone and --window must be numbers" >&2; exit 2 ;;
esac
[ "$WINDOW" -ge 3 ] || { echo "--window below 3 matches boilerplate, not code" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2
git rev-parse --git-dir >/dev/null 2>&1 || { echo "FATAL: not a git repository" >&2; exit 2; }

STRIP_AWK="$REPO_ROOT/scripts/lib/source-strip.awk"
RULES_AWK="$REPO_ROOT/scripts/lib/optimizer-rules.awk"
CLONES_AWK="$REPO_ROOT/scripts/lib/optimizer-clones.awk"
for a in "$STRIP_AWK" "$RULES_AWK" "$CLONES_AWK"; do
    [ -f "$a" ] || { echo "FATAL: missing $a" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# --- File selection ----------------------------------------------------------

is_source() {
    case "$1" in
        *.kt|*.java|*.ts|*.tsx|*.js|*.jsx|*.astro|*.go) return 0 ;;
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

# One place where "is this a file we scan" is decided, because the scope list
# and the clone corpus are built from different sources and must agree — a file
# scanned in one and skipped in the other reports a clone with no partner.
filter_sources() {
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        [ -f "$f" ] || continue
        is_source "$f" || continue
        is_excluded "$f" && continue
        printf '%s\n' "$f"
    done
}

# Resolved once: every later step asks the same question about the same ref.
BASE_REF="$BASE"
git rev-parse --verify --quiet "$BASE_REF" >/dev/null || BASE_REF="main"
git rev-parse --verify --quiet "$BASE_REF" >/dev/null || BASE_REF=""
MERGE_BASE=""
[ -n "$BASE_REF" ] && MERGE_BASE="$(git merge-base HEAD "$BASE_REF" 2>/dev/null || true)"

# The files this run is responsible for. Only the diff mode is subtle: it
# unions the merge-base delta, uncommitted work and untracked files, because
# all three are "what this change did" and only the first survives a commit.
collect_scope() {
    case "$MODE" in
        all)   git ls-files ;;
        paths)
            for t in "${TARGETS[@]}"; do
                [ -e "$t" ] || { echo "FATAL: no such path: $t" >&2; exit 2; }
                if [ -d "$t" ]; then git ls-files -- "$t"; else printf '%s\n' "$t"; fi
            done
            ;;
        diff)
            # Merge-base so a stale local branch does not report the whole
            # upstream delta as "yours". Untracked files count as fully new.
            [ -n "$MERGE_BASE" ] && git diff --name-only --diff-filter=ACMR "$MERGE_BASE" 2>/dev/null
            git diff --name-only --diff-filter=ACMR HEAD 2>/dev/null
            git ls-files --others --exclude-standard
            ;;
    esac
}

collect_scope | sort -u | filter_sources > "$TMP/scope.txt"

SCOPE_N=$(wc -l < "$TMP/scope.txt" | tr -d ' ')
if [ "$SCOPE_N" -eq 0 ]; then
    echo "optimizer-scan: no source files in scope (mode=$MODE) — nothing to check"
    exit 0
fi

# --- Which lines are new -----------------------------------------------------
#
# One "<path>\t<lineno>" per added line. In --all / path mode the set is empty,
# so every finding lands as REVIEW: an audit reports, it does not accuse.

: > "$TMP/added.tsv"
: > "$TMP/newfiles.txt"
if [ "$MODE" = "diff" ]; then
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
            { [ -n "$MERGE_BASE" ] && git diff -U0 "$MERGE_BASE" -- "$f" 2>/dev/null
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
            awk -v path="$f" '{ printf "%s\t%d\n", path, NR }' "$f"
        fi
    done < "$TMP/scope.txt" | sort -u > "$TMP/added.tsv"

    { [ -n "$MERGE_BASE" ] && git diff --name-only --diff-filter=A "$MERGE_BASE" 2>/dev/null
      git ls-files --others --exclude-standard; } | sort -u > "$TMP/newfiles.txt"
fi

# awk needs the language to pick its comment and literal syntax; astro is
# TypeScript plus the island directives, so it rides on `ts` with a flag and
# gets its own batch.
#
# Batching matters more than it looks: the clone corpus is every tracked source
# file, and spawning one awk per file costs several times what the hashing
# does. Both engines reset their per-file state at FNR == 1 so a batch behaves
# exactly like the files scanned one at a time.
batch_of() {
    case "$1" in
        *.kt)    echo kt ;;
        *.java)  echo java ;;
        *.go)    echo go ;;
        *.astro) echo astro ;;
        *)       echo ts ;;
    esac
}

# Partitions a file list into one list per language, so each engine runs once
# per language instead of once per file.
split_batches() {   # split_batches <listfile> <prefix>
    rm -f "$2".{kt,java,go,ts,astro}
    awk -v prefix="$2" '
        NF == 0 { next }
        {
            b = "ts"
            if ($0 ~ /\.kt$/)         b = "kt"
            else if ($0 ~ /\.java$/)  b = "java"
            else if ($0 ~ /\.go$/)    b = "go"
            else if ($0 ~ /\.astro$/) b = "astro"
            print > (prefix "." b)
        }' "$1"
}

# xargs may split an over-long argument list into several invocations. That is
# harmless here precisely because state resets per file.
run_batch() {   # run_batch <prefix> <rulesfile> [extra awk -v args...]
    local prefix="$1" rules="$2"; shift 2
    local b lang isastro
    for b in kt java go ts astro; do
        [ -s "$prefix.$b" ] || continue
        lang="$b"; isastro=0
        if [ "$b" = "astro" ]; then lang="ts"; isastro=1; fi
        tr '\n' '\0' < "$prefix.$b" \
        | xargs -0 awk -v lang="$lang" -v isastro="$isastro" "$@" \
              -f "$STRIP_AWK" -f "$rules" 2>/dev/null
    done
}

: > "$TMP/findings.tsv"

# --- Hot-path rules ----------------------------------------------------------

if [ "$DO_RULES" -eq 1 ]; then
    split_batches "$TMP/scope.txt" "$TMP/scopebatch"
    run_batch "$TMP/scopebatch" "$RULES_AWK" >> "$TMP/findings.tsv"
fi

# --- Clone detection ---------------------------------------------------------
#
# The corpus is the whole repo even in diff mode (see the scope note above), so
# "you pasted this from somewhere else" is findable. Only blocks that touch a
# file in scope get reported.

CORPUS_N=0
if [ "$DO_CLONES" -eq 1 ]; then
    if [ "$MODE" = "all" ]; then
        cp "$TMP/scope.txt" "$TMP/corpus.txt"
    else
        # The corpus is every tracked file in the languages this change
        # touched. Dropping the others is free: a Kotlin block and a TSX block
        # are lexed differently and can never hash to the same window, so they
        # were never going to pair. A backend-only change stops paying for
        # 700 frontend files.
        split_batches "$TMP/scope.txt" "$TMP/langprobe"
        : > "$TMP/scopeexts.txt"
        for b in kt java go ts astro; do
            [ -s "$TMP/langprobe.$b" ] && printf '%s\n' "$b" >> "$TMP/scopeexts.txt"
        done
        git ls-files | filter_sources | awk -v exts="$(tr '\n' ' ' < "$TMP/scopeexts.txt")" '
            BEGIN { split(exts, a, " "); for (i in a) if (a[i] != "") want[a[i]] = 1 }
            {
                b = "ts"
                if ($0 ~ /\.kt$/)         b = "kt"
                else if ($0 ~ /\.java$/)  b = "java"
                else if ($0 ~ /\.go$/)    b = "go"
                else if ($0 ~ /\.astro$/) b = "astro"
                if (b in want) print
            }' > "$TMP/corpus.raw"
        rm -f "$TMP/langprobe".{kt,java,go,ts,astro}

        # git ls-files does not know about a file this change just created, and
        # a brand-new file is the likeliest place for pasted code to be. Union
        # the scope in, or the detector never hashes the very thing it is here
        # to check.
        cat "$TMP/corpus.raw" "$TMP/scope.txt" | sort -u > "$TMP/corpus.txt"
    fi
    CORPUS_N=$(wc -l < "$TMP/corpus.txt" | tr -d ' ')

    split_batches "$TMP/corpus.txt" "$TMP/corpusbatch"
    run_batch "$TMP/corpusbatch" "$CLONES_AWK" -v W="$WINDOW" > "$TMP/windows.tsv"

    # Windows whose shape occurs more than once anywhere in the corpus.
    cut -f2 "$TMP/windows.tsv" | sort | uniq -d > "$TMP/duphash.txt"
    awk -F'\t' 'NR==FNR { d[$1]=1; next } ($2 in d)' "$TMP/duphash.txt" "$TMP/windows.tsv" \
        > "$TMP/members.tsv"

    # Merge overlapping windows back into blocks, then attach the other places
    # each block occurs. Reporting windows instead would turn one 30-line
    # duplication into 25 findings.
    sort -t$'\t' -k1,1 -k3,3n "$TMP/members.tsv" \
    | awk -F'\t' -v minlines="$MIN_CLONE" '
        # First pass over the sorted stream is impossible for the partner map,
        # so build it in memory: hash -> "path:line path:line ...".
        { where[$2] = where[$2] " " $1 ":" $3
          hashes[NR] = $2; pathv[NR] = $1; sv[NR] = $3; ev[NR] = $4; n = NR }
        END {
            for (i = 1; i <= n; i++) {
                if (pathv[i] == cp && sv[i] <= ce + 1) {
                    if (ev[i] > ce) ce = ev[i]
                    hs = hs " " hashes[i]
                    continue
                }
                flush()
                cp = pathv[i]; cs = sv[i]; ce = ev[i]; hs = hashes[i]
            }
            flush()
        }
        function flush(   parts, k, j, loc, colon, p, l, seen, out, cnt) {
            if (cp == "") return
            if (ce - cs + 1 < minlines) { cp = ""; return }
            cnt = split("", seen)
            out = ""; cnt = 0
            k = split(hs, parts, " ")
            for (j = 1; j <= k; j++) {
                if (parts[j] == "") continue
                m = split(where[parts[j]], loc, " ")
                for (q = 1; q <= m; q++) {
                    if (loc[q] == "") continue
                    colon = length(loc[q]) - index(reverse(loc[q]), ":")
                    p = substr(loc[q], 1, colon)
                    l = substr(loc[q], colon + 2)
                    if (p == cp && l + 0 >= cs && l + 0 <= ce) continue
                    if (p in seen) continue
                    seen[p] = 1
                    cnt++
                    if (cnt <= 3) out = out (out == "" ? "" : ", ") loc[q]
                }
            }
            if (out != "") {
                if (cnt > 3) out = out " (+" (cnt - 3) " more)"
                printf "%s\t%d\t%d\t%d\t%s\n", cp, cs, ce, ce - cs + 1, out
            }
            cp = ""
        }
        function reverse(s,   i, r) { r = ""; for (i = length(s); i > 0; i--) r = r substr(s, i, 1); return r }
    ' > "$TMP/blocks.tsv"

    # Fold blocks into the same finding stream the rules use, so severity is
    # decided in exactly one place for both halves of the gate.
    while IFS=$'\t' read -r path start end len partners; do
        [ -n "$path" ] || continue
        grep -qxF "$path" "$TMP/scope.txt" || continue
        printf '%s\tDUP-BLOCK\t%s\t%s lines also at %s\n' "$path" "$start" "$len" "$partners" \
            >> "$TMP/findings.tsv"
    done < "$TMP/blocks.tsv"
fi

# --- Severity ----------------------------------------------------------------

: > "$TMP/block.txt"
: > "$TMP/review.txt"

# One awk pass rather than a grep per finding. An audit produces four figures
# of findings, and at two process spawns each the classification cost more
# than the whole scan that produced them.
awk -F'\t' -v blockf="$TMP/block.txt" -v reviewf="$TMP/review.txt" '
    FILENAME == addedf   { added[$1 "\t" $2] = 1; next }
    FILENAME == newf     { newfile[$0] = 1; next }
    {
        if (($1 "\t" $3) in added) sev = "BLOCK"
        else if ($2 == "DUP-BLOCK" && ($1 in newfile)) sev = "BLOCK"
        else sev = "REVIEW"
        printf "%s:%s  [%s] %s\n", $1, $3, $2, $4 > (sev == "BLOCK" ? blockf : reviewf)
    }
' addedf="$TMP/added.tsv" newf="$TMP/newfiles.txt" \
  "$TMP/added.tsv" "$TMP/newfiles.txt" "$TMP/findings.tsv"

# The offending source line is only fetched when asked for: it is one seek per
# finding, which is affordable for a diff and not for an audit.
if [ "$VERBOSE" -eq 1 ]; then
    for lst in "$TMP/block.txt" "$TMP/review.txt"; do
        [ -s "$lst" ] || continue
        while IFS= read -r entry; do
            printf '%s\n' "$entry"
            loc=${entry%%  \[*}
            src=$(sed -n "${loc##*:}p" "${loc%:*}" 2>/dev/null | sed 's/^[[:space:]]*//' | cut -c1-110)
            [ -n "$src" ] && printf '        > %s\n' "$src"
        done < "$lst" > "$lst.v"
        mv "$lst.v" "$lst"
    done
fi

# --- Report ------------------------------------------------------------------

# grep -c exits 1 on no match, so the default has to be applied separately or
# every later numeric test breaks on an empty string.
BLOCK_N=$(grep -c '\[' "$TMP/block.txt" 2>/dev/null); BLOCK_N=${BLOCK_N:-0}
REVIEW_N=$(grep -c '\[' "$TMP/review.txt" 2>/dev/null); REVIEW_N=${REVIEW_N:-0}

corpus_note=""
[ "$DO_CLONES" -eq 1 ] && corpus_note="  clone corpus=$CORPUS_N files (window=$WINDOW, min=$MIN_CLONE lines)"
echo "optimizer-scan: mode=$MODE  files=$SCOPE_N$corpus_note"

if [ "$BLOCK_N" -gt 0 ]; then
    echo
    echo "BLOCK ($BLOCK_N) — introduced by this change, fix before calling it done:"
    sort "$TMP/block.txt" | sed 's/^/  /'
fi

if [ "$REVIEW_N" -gt 0 ]; then
    echo
    echo "REVIEW ($REVIEW_N) — pre-existing in files in scope; decide and say so, do not silently skip:"
    sort "$TMP/review.txt" | sed 's/^/  /'
fi

if [ "$BLOCK_N" -eq 0 ] && [ "$REVIEW_N" -eq 0 ]; then
    echo "optimizer-scan: OK — no findings"
    exit 0
fi

echo
cat <<'EOF'
Findings are candidates, not verdicts. A rule fires on a shape, and a shape is
slow only on a hot path — measure before rewriting, and say so when a finding
is deliberate. DUP-BLOCK reports that code appears twice; whether the two
copies are one concept or two that happen to rhyme is the judgment the gate
cannot make. Both halves live in .claude/skills/optimizer/.
EOF

if [ "$BLOCK_N" -gt 0 ]; then exit 1; fi
if [ "$STRICT" -eq 1 ] && [ "$REVIEW_N" -gt 0 ]; then exit 1; fi
exit 0
