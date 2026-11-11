#!/usr/bin/env bash
#
# check-skill-sync.sh — detect real drift between the two skill trees.
#
#   .claude/skills/  (Claude Code)
#   .agents/skills/  (Codex)
#
# They are two renderings of the same skill set, and the sync obligation is
# two-way: an edit in either tree must land in the other in the same commit
# (CLAUDE.md §"Tooling Conventions" / AGENTS.md §Skills). `.claude/skills/` is
# only the tie-breaker when the two already disagree.
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
    count_hits sync_banner     "$f" '^> \*\*Sync policy'
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
    | awk '/^> \*\*Sync policy/{skip=1} skip&&/^>/{next} skip&&!/^>/{skip=0} {print}' \
    | grep -v 'superpowers:' \
    | sed -E 's/[[:space:]]+$//' \
    | grep -v '^$'
}

# --- Tier 1: structural ------------------------------------------------------

# Local-only skills, gitignored in .claude/.gitignore and deliberately absent
# from .agents/skills/. Space-separated top-level skill directory names.
EXCLUDED_SKILLS="cov-security-review"

list_rel() {  # list_rel <root> -> paths relative to that root, sorted
    local skill
    ( cd "$1" && find . -type f -name '*.md' | sed 's|^\./||' | sort ) \
    | while IFS= read -r rel; do
        skill="${rel%%/*}"
        case " $EXCLUDED_SKILLS " in *" $skill "*) continue ;; esac
        echo "$rel"
    done
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

# --- Tier 3: description length ----------------------------------------------
#
# The Agent Skills format caps `description` at 1024 characters. Codex refuses to
# load a skill over the cap and Claude Code truncates it, so an over-long
# description silently drops the skill on one harness. Counts the folded text
# the way YAML does (lines joined with single spaces).

for f in "$CLAUDE_ROOT"/*/SKILL.md "$AGENTS_ROOT"/*/SKILL.md; do
    len=$(awk '
        /^---$/ { fm++; next }
        fm != 1 { next }
        /^description:/ { in_desc = 1; sub(/^description:[[:space:]]*[>|]?[-+]?[[:space:]]*/, ""); text = $0; next }
        in_desc && /^[[:space:]]/ { sub(/^[[:space:]]+/, ""); text = (text == "" ? $0 : text " " $0); next }
        in_desc { in_desc = 0 }
        END { print length(text) }' "$f")
    [ "$len" -gt 1024 ] && note "$(basename "$(dirname "$f")"): DESCRIPTION-TOO-LONG: $f description is $len chars (max 1024)"
done

# --- Tier 4: subagents --------------------------------------------------------
#
# .claude/agents/<name>.md (frontmatter + body) and .codex/agents/<name>.toml
# (name, description, developer_instructions = '''body''') must pair up with the
# same description and the same instructions.

CLAUDE_AGENTS=".claude/agents"
CODEX_AGENTS=".codex/agents"

md_desc()   { awk '/^---$/{fm++; next} fm!=1{next} /^description:/{d=1; next} d&&/^[[:space:]]/{sub(/^[[:space:]]+/,""); t=(t==""?$0:t" "$0); next} d{d=0} END{print t}' "$1"; }
md_body()   { awk 'fm>=2{print; next} /^---$/{fm++}' "$1" | sed -E 's/[[:space:]]+$//' | grep -v '^$'; }
toml_desc() { sed -nE 's/^description = "(.*)"$/\1/p' "$1"; }
toml_body() { awk "/^developer_instructions = '''\$/{b=1; next} b&&/^'''\$/{b=0} b" "$1" | sed -E 's/[[:space:]]+$//' | grep -v '^$'; }

AGENT_COUNT=0
for c in "$CLAUDE_AGENTS"/*.md; do
    [ -e "$c" ] || continue
    n="$(basename "$c" .md)"; x="$CODEX_AGENTS/$n.toml"
    AGENT_COUNT=$((AGENT_COUNT + 1))
    [ -f "$x" ] || { note "agent $n: MISSING-IN-CODEX: $c has no $x"; continue; }
    [ "$(md_desc "$c")" = "$(toml_desc "$x")" ] || note "agent $n: DESCRIPTION-DRIFT between $c and $x"
    if ! diff -q <(md_body "$c") <(toml_body "$x") >/dev/null; then
        note "agent $n: CONTENT-DRIFT: instructions in $c and $x differ"
        [ "$VERBOSE" -eq 1 ] && diff <(md_body "$c") <(toml_body "$x") | sed 's/^/      /'
    fi
done
for x in "$CODEX_AGENTS"/*.toml; do
    [ -e "$x" ] || continue
    n="$(basename "$x" .toml)"
    [ -f "$CLAUDE_AGENTS/$n.md" ] || note "agent $n: MISSING-IN-CLAUDE: $x has no $CLAUDE_AGENTS/$n.md"
done

# --- Report ------------------------------------------------------------------

echo "skill-sync: compared $(wc -l < "$TMP/claude.files" | tr -d ' ') file(s) under $CLAUDE_ROOT against $AGENTS_ROOT, and $AGENT_COUNT agent(s) under $CLAUDE_AGENTS against $CODEX_AGENTS"

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
sync obligation is two-way, so either tree may hold the newer content. The
Claude copy is the tie-breaker only when neither is clearly newer, and it has
been the stale one before (e2ejs documented a scanner implementation that no
longer existed). Port the correct content into the other tree, then re-run.
Use --verbose to see the differing lines.
EOF
exit 1
