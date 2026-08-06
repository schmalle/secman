#!/usr/bin/env bash
#
# check-skill-sync.sh — detect real drift between the two skill trees.
#
#   .claude/skills/  (canonical, Claude Code)
#   .agents/skills/  (mirror, Codex)
#
# CLAUDE.md requires these to move together. They did not: 7 of 8 skills had
# drifted when this check was written, including one where the *Codex* copy was
# the factually correct one and one where the two copies had forked into
# different documents.
#
# Two tiers, because they have very different reliability:
#
#   Tier 1 — structural. Every file under one tree must exist under the other.
#            Exact, no pattern matching, cannot produce a false positive. This
#            is what catches a whole missing references/ directory.
#   Tier 2 — normalized content. Canonicalizes the handful of legitimate
#            Claude<->Codex differences to sentinel tokens, then diffs.
#
# REPORT ONLY. This script never edits either tree. It cannot know which side is
# correct — history shows both have been right — so it refuses to guess.
#
# Exit codes:  0 = in sync   1 = real drift found   2 = usage/environment error
#
# Usage: ./scripts/check-skill-sync.sh [--verbose]

set -uo pipefail

VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1
[ -n "${1:-}" ] && [ "${1:-}" != "--verbose" ] && {
    echo "usage: $0 [--verbose]" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2

CLAUDE_ROOT=".claude/skills"
AGENTS_ROOT=".agents/skills"

for d in "$CLAUDE_ROOT" "$AGENTS_ROOT"; do
    [ -d "$d" ] || { echo "FATAL: $d does not exist (run from the repo root)" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

DRIFT=0
declare -a FINDINGS=()

note() { FINDINGS+=("$1"); DRIFT=1; }

# --- Normalization -----------------------------------------------------------
#
# Each rule collapses one legitimate Claude<->Codex difference to a sentinel so
# it cannot register as drift. Rules are deliberately few: every rule is a place
# where real drift could hide, so the set stays as small as the corpus allows.
#
# RULE_NAMES is used to report which rules fired, and to warn about rules that
# fired nowhere — a dead rule usually means the wording moved on and the rule is
# no longer protecting anything, which is how a checker silently rots.

# Counters live in files rather than an associative array: macOS ships bash 3.2,
# which has no `declare -A`, and this script must run on the dev machines as-is.
RULE_NAMES="sandbox_escape outside_sandbox sync_banner superpowers skills_root ask_user"
mkdir -p "$TMP/hits"
for r in $RULE_NAMES; do echo 0 > "$TMP/hits/$r"; done

count_hits() {  # count_hits <rule> <file> <extended-regex>
    local n prev
    n=$(grep -Ec "$3" "$2" 2>/dev/null || true)
    prev=$(cat "$TMP/hits/$1")
    echo $(( prev + n )) > "$TMP/hits/$1"
}

normalize() {   # normalize <file> -> canonical form on stdout
    local f="$1"

    count_hits sandbox_escape  "$f" 'dangerouslyDisableSandbox|sandbox_permissions'
    count_hits outside_sandbox "$f" 'outside the sandbox|outside any sandbox'
    count_hits sync_banner     "$f" '^> \*\*Sync policy\*\*'
    count_hits superpowers     "$f" 'superpowers:'
    count_hits skills_root     "$f" '\.(claude|agents)/skills/'
    count_hits ask_user        "$f" 'AskUserQuestion|ask the user directly'

    sed -E \
        -e 's/ask the user \(in Claude Code, `AskUserQuestion`\)/<<ASK_USER>>/g' \
        -e 's/ask the user directly/<<ASK_USER>>/g' \
        -e 's/(Bash tool `)?dangerouslyDisableSandbox: true(`)?/<<SANDBOX_ESCAPE>>/g' \
        -e 's/`?sandbox_permissions: "require_escalated"`?/<<SANDBOX_ESCAPE>>/g' \
        -e 's/[Ii]n Codex, run these commands with //g' \
        -e 's/\(outside the sandbox\)/<<OUTSIDE_SANDBOX>>/g' \
        -e 's/[Tt]his start\/restart command must be executed outside the sandbox\.?/<<OUTSIDE_SANDBOX>>/g' \
        -e 's/outside the sandbox \/ with escalated permissions/<<OUTSIDE_SANDBOX>>/g' \
        -e 's/outside (the|any) sandbox/<<OUTSIDE_SANDBOX>>/g' \
        -e 's/\.(claude|agents)\/skills\//<<SKILLS_ROOT>>/g' \
        "$f" \
    | awk '/^> \*\*Sync policy\*\*/{skip=1} skip&&/^>/{next} skip&&!/^>/{skip=0} {print}' \
    | grep -v 'superpowers:' \
    | sed -E 's/[[:space:]]+$//' \
    | grep -v '^$'
}

# --- Tier 1: structural ------------------------------------------------------

list_rel() {  # list_rel <root> -> paths relative to that root, sorted
    ( cd "$1" && find . -type f -name '*.md' | sed 's|^\./||' | sort )
}

list_rel "$CLAUDE_ROOT" > "$TMP/claude.files"
list_rel "$AGENTS_ROOT" > "$TMP/agents.files"

while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    note "${rel%%/*}: MISSING-IN-AGENTS: .claude/skills/$rel has no counterpart under .agents/skills/"
done < <(comm -23 "$TMP/claude.files" "$TMP/agents.files")

while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    note "${rel%%/*}: MISSING-IN-CLAUDE: .agents/skills/$rel has no counterpart under .claude/skills/"
done < <(comm -13 "$TMP/claude.files" "$TMP/agents.files")

# --- Tier 2: normalized content ----------------------------------------------

while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    c="$CLAUDE_ROOT/$rel"
    a="$AGENTS_ROOT/$rel"
    normalize "$c" > "$TMP/c.norm"
    normalize "$a" > "$TMP/a.norm"
    if ! diff -q "$TMP/c.norm" "$TMP/a.norm" >/dev/null 2>&1; then
        added=$(diff "$TMP/c.norm" "$TMP/a.norm" | grep -c '^>' || true)
        removed=$(diff "$TMP/c.norm" "$TMP/a.norm" | grep -c '^<' || true)
        note "${rel%%/*}: CONTENT-DRIFT: $rel differs after normalization (${removed} lines only in .claude, ${added} only in .agents)"
        if [ "$VERBOSE" -eq 1 ]; then
            diff "$TMP/c.norm" "$TMP/a.norm" | sed 's/^/      /'
        fi
    fi
done < <(comm -12 "$TMP/claude.files" "$TMP/agents.files")

# --- Report ------------------------------------------------------------------

echo "skill-sync: compared $(wc -l < "$TMP/claude.files" | tr -d ' ') file(s) under $CLAUDE_ROOT against $AGENTS_ROOT"

# A rule that matched nothing anywhere is no longer protecting anything. Say so:
# silent regex rot turns this check into noise the reader learns to ignore.
for r in $RULE_NAMES; do
    hits=$(cat "$TMP/hits/$r")
    if [ "$hits" -eq 0 ]; then
        echo "skill-sync: WARNING: normalization rule '$r' matched nothing — the wording it targets may have changed; re-check the rule before trusting a green result"
    elif [ "$VERBOSE" -eq 1 ]; then
        echo "skill-sync: normalized '$r' x${hits}"
    fi
done

if [ "$DRIFT" -eq 0 ]; then
    echo "skill-sync: OK — both trees agree"
    exit 0
fi

echo "skill-sync: DRIFT — ${#FINDINGS[@]} finding(s)"
printf '  %s\n' "${FINDINGS[@]}"
cat <<'EOF'

Not auto-fixable, deliberately. Decide per finding which side is correct — the
Claude copy is authoritative by policy, but it has been the stale one before
(e2ejs documented a scanner implementation that no longer existed). Port the
correct content, then re-run. Use --verbose to see the differing lines.
EOF
exit 1
