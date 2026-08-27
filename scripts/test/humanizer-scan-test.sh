#!/usr/bin/env bash
#
# humanizer-scan-test.sh — self-test for scripts/humanizer-scan.sh.
#
# A hygiene gate fails in two directions and only one of them is visible.
# A rule that stopped firing looks exactly like clean code, so the repo drifts
# while the gate reports green. Every rule therefore gets both halves here:
#
#   FIRES   — code that violates the rule must produce the finding
#   SILENT  — code that respects it must produce nothing
#
# The SILENT cases are the ones that matter most in practice: they are what
# stops a noisy rule from being widened until nobody reads the output. Several
# of them encode house style this repo actually uses (`// --- Section ---`
# dividers, Go's short receiver names, self-describing test names) and exist
# because early versions of the scanner flagged all three.
#
# Exit codes:  0 = all assertions pass   1 = a assertion failed   2 = environment error
#
# Usage: ./scripts/test/humanizer-scan-test.sh [--verbose]

set -uo pipefail

VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RULES="$REPO_ROOT/scripts/lib/humanizer-rules.awk"
SCAN="$REPO_ROOT/scripts/humanizer-scan.sh"
[ -f "$RULES" ] && [ -x "$SCAN" ] || { echo "FATAL: scanner not found at $SCAN" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0

# Runs the rule engine over a fixture written inline. Kept as one helper so a
# test case is three lines: name, language, fixture, expectation.
run_rules() {   # run_rules <lang> <file>
    awk -v path="$2" -v lang="$1" -v maxfile=1000 -v maxfunc=100 -v warnfunc=50 \
        -f "$RULES" "$2"
}

# Asserts a rule DOES trigger. The failure message prints what came back so a
# broken fixture is distinguishable from a broken rule.
fires() {       # fires <name> <lang> <file> <rule>
    local out
    out="$(run_rules "$2" "$3")"
    if printf '%s\n' "$out" | grep -q "^$4"; then
        PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   FIRES  $1"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL FIRES  $1 — expected $4, got: ${out:-<nothing>}"
    fi
    return 0
}

# Asserts a rule does NOT trigger. These guard against a widened rule, which
# is the failure mode that hides rather than shouts.
silent() {      # silent <name> <lang> <file> <rule>
    local out
    out="$(run_rules "$2" "$3" | grep "^$4" || true)"
    if [ -z "$out" ]; then
        PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   SILENT $1"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL SILENT $1 — unexpected: $out"
    fi
    return 0
}

# Asserts on the finding's text, used where the exact measured number matters.
says() {        # says <name> <lang> <file> <substring>
    local out
    out="$(run_rules "$2" "$3")"
    if printf '%s\n' "$out" | grep -qF "$4"; then
        PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   SAYS   $1"
    else
        FAIL=$((FAIL + 1)); echo "  FAIL SAYS   $1 — expected '$4', got: ${out:-<nothing>}"
    fi
    return 0
}

echo "humanizer-scan-test: rule engine"

# --- Length measurement ------------------------------------------------------
#
# The exact line count is asserted, not just "it fired". Every desync bug this
# scanner has had (Go raw strings, bash heredocs, shell globs containing `/*`)
# showed up as a wildly wrong number while the rule still fired.

{ echo "package p"; echo "func Long() {"
  for i in $(seq 1 120); do echo "	step$i := $i"; done
  echo "}"; } > "$WORK/long.go"
says "go function length is exact" go "$WORK/long.go" "is 122 lines"

printf 'package p\n\nfunc WithRawString() string {\n\treturn `{\n}\n{`\n}\n\nfunc After() int {\n\treturn 1\n}\n' > "$WORK/raw.go"
silent "go raw string does not desync the next function" go "$WORK/raw.go" "FUNC"

cat > "$WORK/here.sh" <<'SH'
#!/usr/bin/env bash
# Writes the template.
emit_template() {
    cat <<'EOF'
{ "nested": { "braces": true } }
EOF
}

# Reads it back.
read_template() {
    cat /dev/null
}
SH
silent "bash heredoc does not desync the next function" sh "$WORK/here.sh" "FUNC"

cat > "$WORK/glob.sh" <<'SH'
#!/usr/bin/env bash
# Filters build output.
skip_generated() {
    case "$1" in
        */node_modules/*|*/build/*) return 0 ;;
    esac
    return 1
}
SH
silent "shell glob containing /* is not read as a comment" sh "$WORK/glob.sh" "FUNC"

printf '/** Wrapper. */\nclass C {\n    fun f(): String {\n        val brace = "}"\n        return brace\n    }\n\n    fun g(): Int {\n        return 1\n    }\n}\n' > "$WORK/str.kt"
silent "brace inside a Kotlin string literal is ignored" kt "$WORK/str.kt" "FUNC"

# --- Documentation -----------------------------------------------------------

printf 'package p\n\nfunc Exported(a int) int {\n\tb := a\n\tc := b\n\treturn c\n}\n' > "$WORK/undoc.go"
fires "undocumented exported function" go "$WORK/undoc.go" "UNDOC-DECL"

printf 'package p\n\n// Exported doubles the input.\nfunc Exported(a int) int {\n\tb := a\n\tc := b\n\treturn c\n}\n' > "$WORK/doc.go"
silent "documented exported function" go "$WORK/doc.go" "UNDOC-DECL"

printf 'package p\n\nfunc TestOwnerCannotReadForeignAsset(t *testing.T) {\n\tx := 1\n\ty := x\n\t_ = y\n}\n' > "$WORK/t_test.go"
silent "a self-describing test name needs no doc comment" go "$WORK/t_test.go" "UNDOC-DECL"

# @Test is rarely the line directly above `fun`. This case also pins the awk
# dialect: `\b` is a GNU extension that BSD awk reads as a backspace, so the
# annotation lookback silently matched nothing until the boundary was spelled out.
printf '/** Wrapper. */\nclass C {\n    @Test\n    @DisplayName("VSD-001: something")\n    fun anchorIsOnByDefault() {\n        val a = 1\n        val b = a\n        assertThat(b)\n    }\n}\n' > "$WORK/annot.kt"
silent "@Test found past an intervening @DisplayName" kt "$WORK/annot.kt" "UNDOC-DECL"

printf '/** Wrapper. */\nclass T : McpTool {\n    override suspend fun execute(args: Map<String, Any>): Int {\n        val n = 1\n        return n\n    }\n}\n' > "$WORK/ovrname.kt"
silent "an override does not get to choose its name" kt "$WORK/ovrname.kt" "NAME-VAGUE"

printf '/** Wrapper. */\nclass C {\n    override fun toString(): String {\n        val out = "x"\n        return out\n    }\n}\n' > "$WORK/ovr.kt"
silent "override inherits the parent contract" kt "$WORK/ovr.kt" "UNDOC-DECL"

printf '/** Wrapper. */\nclass C {\n    fun id(): Int {\n        return 1\n    }\n}\n' > "$WORK/tiny.kt"
silent "a three-line function needs no prose" kt "$WORK/tiny.kt" "UNDOC-DECL"

# --- Naming ------------------------------------------------------------------

printf 'package p\n\n// Run does the thing.\nfunc Run() {\n\ttmp := 1\n\t_ = tmp\n}\n' > "$WORK/vague.go"
fires "junk variable name" go "$WORK/vague.go" "NAME-VAGUE"

printf '/** Wrapper. */\nclass C {\n    /** Does it. */\n    fun doStuff(): Int {\n        return 1\n    }\n}\n' > "$WORK/vfn.kt"
fires "junk function name" kt "$WORK/vfn.kt" "NAME-VAGUE"

printf 'package p\n\n// Handler serves.\nfunc Handler(w int, r int) {\n\ts := w + r\n\t_ = s\n}\n' > "$WORK/short.go"
silent "short names in a short scope are idiomatic, not vague" go "$WORK/short.go" "NAME-VAGUE"

{ echo "package p"; echo "// Big is big."; echo "func Big() {"; echo "	s := 0"
  for i in $(seq 1 70); do echo "	s += $i"; done
  echo "}"; } > "$WORK/shortlong.go"
fires "the same short name inside a long function is a reading cost" go "$WORK/shortlong.go" "NAME-VAGUE"

# --- Generator residue -------------------------------------------------------

printf '/**\n * Imports things.\n *\n * Feature: 032-servers-query-import\n * Tasks: T019, T020\n */\nclass Importer\n' > "$WORK/scaffold.kt"
fires "spec-kit tracking metadata in a doc comment" kt "$WORK/scaffold.kt" "COMMENT-SCAFFOLD"

printf 'package p\n\n// --- JWS / JWK -------------------------------------------------------------\n\n// Sign signs.\nfunc Sign() {}\n' > "$WORK/divider.go"
silent "the repo's own --- section divider is house style" go "$WORK/divider.go" "COMMENT-SCAFFOLD"

printf '/** Wrapper. */\nclass C {\n    @BeforeEach\n    fun setUp() {\n        val a = 1\n        val b = a\n        val c = b\n    }\n}\n' > "$WORK/before.kt"
silent "a JUnit lifecycle hook is not prose that needs a comment" kt "$WORK/before.kt" "UNDOC-DECL"

printf 'package p\n\n// Added by Markus on 2026-03-04\n// Sign signs.\nfunc Sign() {}\n' > "$WORK/changelog.go"
fires "authorship changelog comment" go "$WORK/changelog.go" "COMMENT-SCAFFOLD"

# "Added by <a feature>" is causal explanation, not authorship. An early version
# of the rule matched any noun after `by` and flagged a genuinely good comment.
printf 'package p\n\n// Added by the product-classification feature; the lookup was never\n// updated, so this failed with NoSuchMethodException instead of asserting.\nfunc Sign() {}\n' > "$WORK/causal.go"
silent "\"Added by <feature>\" explains a cause and is not a changelog" go "$WORK/causal.go" "COMMENT-SCAFFOLD"

printf 'package p\n\n// Step 1: open the file\n// Sign signs.\nfunc Sign() {}\n' > "$WORK/steps.go"
fires "numbered Step N narration" go "$WORK/steps.go" "COMMENT-SCAFFOLD"

# --- Severity model ----------------------------------------------------------
#
# Driver-level, in a throwaway repo: the whole point of the gate is that new
# code blocks and inherited code does not, and that distinction is invisible
# to the rule engine.

echo "humanizer-scan-test: severity model"

REPO="$WORK/repo"
mkdir -p "$REPO/scripts/lib" "$REPO/pkg"
cp "$SCAN" "$REPO/scripts/" && cp "$RULES" "$REPO/scripts/lib/"
git -C "$REPO" init -q .
git -C "$REPO" -c user.email=t@t -c user.name=t commit -q --allow-empty -m init

{ echo "package pkg"; echo "func Inherited() {"
  for i in $(seq 1 120); do echo "	s$i := $i"; done
  echo "}"; } > "$REPO/pkg/legacy.go"

out="$("$REPO/scripts/humanizer-scan.sh" --base HEAD 2>&1)"; rc=$?
if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q "BLOCK"; then
    PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   FIRES  new code blocks and exits 1"
else
    FAIL=$((FAIL + 1)); echo "  FAIL FIRES  new code should BLOCK and exit 1 (rc=$rc)"
fi

# Emoji: a finding in a comment, deliberate interface text in a println.
printf 'package pkg\n\n// Ship it \xf0\x9f\x9a\x80\nfunc A() {}\n\n// B prints.\nfunc B() { println("done \xf0\x9f\x94\x97") }\n' > "$REPO/pkg/emoji.go"
out="$("$REPO/scripts/humanizer-scan.sh" --base HEAD 2>&1 | grep "emoji" || true)"
if [ "$(printf '%s' "$out" | grep -c emoji)" -eq 1 ]; then
    PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   FIRES  emoji in a comment, not in CLI output"
else
    FAIL=$((FAIL + 1)); echo "  FAIL FIRES  emoji rule should hit the comment only, got: ${out:-<nothing>}"
fi
rm -f "$REPO/pkg/emoji.go"

git -C "$REPO" add -A
git -C "$REPO" -c user.email=t@t -c user.name=t commit -q -m legacy
printf '\n// Extra is new.\nvar Extra = 1\n' >> "$REPO/pkg/legacy.go"

out="$("$REPO/scripts/humanizer-scan.sh" --base HEAD 2>&1)"; rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "REVIEW"; then
    PASS=$((PASS + 1)); [ "$VERBOSE" -eq 1 ] && echo "  ok   SILENT inherited code reviews and exits 0"
else
    FAIL=$((FAIL + 1)); echo "  FAIL SILENT touching an inherited long function must REVIEW, not BLOCK (rc=$rc)"
fi

# --- Report ------------------------------------------------------------------

echo
if [ "$FAIL" -eq 0 ]; then
    echo "humanizer-scan-test: OK — $PASS assertions passed"
    exit 0
fi
echo "humanizer-scan-test: FAILED — $FAIL of $((PASS + FAIL)) assertions failed"
exit 1
