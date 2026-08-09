#!/usr/bin/env bash
#
# owasp-check.sh — deterministic OWASP Top 10:2021 gate for generated code.
#
# CLAUDE.md §"OWASP Top 10 Compliance" is binding policy, but policy that only
# exists as prose gets partially followed. This script is the machine-checkable
# half: it turns the rules that CAN be checked statically into pass/fail, so a
# violation costs a red exit instead of a reviewer's attention.
#
# It is NOT a replacement for reading the diff. Roughly half the OWASP rules
# here (business invariants, deny-by-default role choice, "is this id actually
# owner-scoped") are semantic and no grep will ever see them. Those live in
# `.claude/skills/secure-code/` and `/security-review`. This script covers the
# mechanical half — the half that keeps getting re-broken.
#
# ---------------------------------------------------------------------------
# Scope model — why the default is diff-scoped
# ---------------------------------------------------------------------------
#
# Run over the whole repo, several rules produce dozens of pre-existing hits
# (91 `findAll()` calls, 58 native queries). A gate that is red on arrival is a
# gate everyone learns to ignore. So by default this checks only what THIS
# change added, against the merge-base with the default branch:
#
#   added line matches a rule            -> BLOCK  (you wrote it, fix it)
#   pre-existing hit in a file you touched -> REVIEW (look, decide, move on)
#
# `--all` scans every tracked source file and is for audits, not for gating.
#
# Exit codes:  0 = no BLOCK findings   1 = BLOCK findings   2 = usage/env error
#
# Usage:
#   ./scripts/owasp-check.sh                 # this change vs origin/main
#   ./scripts/owasp-check.sh --base origin/main
#   ./scripts/owasp-check.sh --all           # whole repo audit
#   ./scripts/owasp-check.sh --verbose       # show the offending line text
#   ./scripts/owasp-check.sh --strict        # REVIEW findings also exit 1

set -uo pipefail

MODE="diff"
BASE="origin/main"
VERBOSE=0
STRICT=0

while [ $# -gt 0 ]; do
    case "$1" in
        --all)     MODE="all" ;;
        --diff)    MODE="diff" ;;
        --verbose) VERBOSE=1 ;;
        --strict)  STRICT=1 ;;
        --base)    shift; [ $# -gt 0 ] || { echo "--base needs a ref" >&2; exit 2; }; BASE="$1" ;;
        -h|--help)
            sed -n '3,40p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 2
git rev-parse --git-dir >/dev/null 2>&1 || { echo "FATAL: not a git repository" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Source files we reason about. Anything else (docs, images, lockfiles) is out
# of scope: a rule that fires on a Markdown code fence is a rule people disable.
SRC_RE='\.(kt|kts|java|ts|tsx|js|jsx|astro|py|sh|sql|ya?ml|json|gradle)$'

# Two files, and only these two, are exempt — both would otherwise be
# permanently red for code that is doing its job:
#
#   scripts/test/owasp-check-test.sh  is *made of* vulnerable fixtures
#   scripts/owasp-check.sh            contains the patterns it hunts for
#
# The exemption is deliberately not "tests are exempt". CLAUDE.md forbids a
# literal credential in "source, tests, scripts or fixtures" — every other test
# file stays in scope. Excluded paths are printed at the end of every run, so
# this stays a visible decision rather than a blind spot.
SELF_RE='^scripts/(owasp-check\.sh|test/owasp-check-test\.sh)$'

# ---------------------------------------------------------------------------
# Collect the scan surface
# ---------------------------------------------------------------------------
#
# changed.files  — paths to consider for file-context rules
# added.lines    — "path<TAB>lineno<TAB>text" for every line this change adds
#
# In --all mode "added" means "every line", which is what makes the same rule
# bodies serve both modes without a second implementation.

collect_diff() {
    local base_sha
    base_sha="$(git merge-base "$BASE" HEAD 2>/dev/null)"
    if [ -z "$base_sha" ]; then
        echo "note: '$BASE' not found — comparing against HEAD (uncommitted work only)" >&2
        base_sha="HEAD"
    fi
    echo "$base_sha" > "$TMP/base_sha"

    # Working tree included on purpose: code that is written but not yet
    # committed is exactly the code this gate exists to catch. Untracked files
    # matter even more — a brand-new generated controller is invisible to
    # `git diff`, and "the file I just created" is the common case here.
    { git diff --name-only --diff-filter=ACMR "$base_sha" -- .
      git ls-files --others --exclude-standard -- .
    } | grep -E "$SRC_RE" | grep -Ev "$SELF_RE" | sort -u > "$TMP/changed.files"

    git diff -U0 "$base_sha" -- . | awk '
        /^\+\+\+ / {
            f = substr($0, 5)
            sub(/^b\//, "", f)
            if (f == "/dev/null") f = ""
            next
        }
        /^@@ / {
            if (match($0, /\+[0-9]+/)) {
                ln = substr($0, RSTART + 1, RLENGTH - 1) + 0
            }
            next
        }
        /^\+/ && f != "" {
            print f "\t" ln "\t" substr($0, 2)
            ln++
        }
    ' | awk -F'\t' -v RE="$SRC_RE" -v SELF="$SELF_RE" '$1 ~ RE && $1 !~ SELF' > "$TMP/added.lines"

    # Every line of an untracked file is an added line.
    git ls-files --others --exclude-standard -- . | grep -E "$SRC_RE" | grep -Ev "$SELF_RE" | while IFS= read -r f; do
        [ -f "$f" ] || continue
        awk -v F="$f" '{ print F "\t" NR "\t" $0 }' "$f" >> "$TMP/added.lines"
    done
}

collect_all() {
    git ls-files | grep -E "$SRC_RE" | grep -Ev "$SELF_RE" | sort -u > "$TMP/changed.files"
    : > "$TMP/added.lines"
    while IFS= read -r f; do
        [ -f "$f" ] || continue
        awk -v F="$f" '{ print F "\t" NR "\t" $0 }' "$f" >> "$TMP/added.lines"
    done < "$TMP/changed.files"
}

if [ "$MODE" = "all" ]; then collect_all; else collect_diff; fi

CHANGED_COUNT=$(wc -l < "$TMP/changed.files" | tr -d ' ')
ADDED_COUNT=$(wc -l < "$TMP/added.lines" | tr -d ' ')

: > "$TMP/findings"

# ---------------------------------------------------------------------------
# Rule primitives
# ---------------------------------------------------------------------------
#
# Findings are "SEV|CAT|RULE|path|line|message|text". Messages must never
# contain a pipe. Every message names the control to reuse — a finding that
# only says "don't do that" gets worked around instead of fixed.

emit() { # emit SEV CAT RULE path line message text
    printf '%s|%s|%s|%s|%s|%s|%s\n' "$1" "$2" "$3" "$4" "$5" "$6" "$7" >> "$TMP/findings"
}

# added_rule RULE SEV CAT PATH_RE CONTENT_RE EXCLUDE_RE MESSAGE
#   EXCLUDE_RE of "-" means no exclusion. Fires on lines this change added.
added_rule() {
    local rule="$1" sev="$2" cat="$3" path_re="$4" content_re="$5" excl="$6" msg="$7"
    awk -F'\t' -v PR="$path_re" -v CR="$content_re" -v EX="$excl" \
        '$1 ~ PR && $3 ~ CR { if (EX != "-" && $3 ~ EX) next; print }' "$TMP/added.lines" \
    | while IFS=$'\t' read -r p l t; do
        emit "$sev" "$cat" "$rule" "$p" "$l" "$msg" "$t"
      done
}

# added_rule_and RULE SEV CAT PATH_RE RE1 RE2 EXCLUDE_RE MESSAGE
#   Same, but the line must match BOTH RE1 and RE2. Several rules are only
#   precise as a conjunction: "contains SELECT" alone matches every log message
#   with the word "from" in it, and a rule that cries wolf 24 times gets muted.
added_rule_and() {
    local rule="$1" sev="$2" cat="$3" path_re="$4" re1="$5" re2="$6" excl="$7" msg="$8"
    awk -F'\t' -v PR="$path_re" -v R1="$re1" -v R2="$re2" -v EX="$excl" \
        '$1 ~ PR && $3 ~ R1 && $3 ~ R2 { if (EX != "-" && $3 ~ EX) next; print }' "$TMP/added.lines" \
    | while IFS=$'\t' read -r p l t; do
        emit "$sev" "$cat" "$rule" "$p" "$l" "$msg" "$t"
      done
}

# file_rule RULE CAT PATH_RE TRIGGER_RE REQUIRED_RE MESSAGE
#   For each changed file matching PATH_RE that contains TRIGGER_RE but lacks
#   REQUIRED_RE. Severity is BLOCK when the trigger itself is on an added line
#   (this change introduced it) and REVIEW when it was already there.
#
#   TRIGGER_RE is evaluated by BOTH grep -E and awk, so it must stay in the
#   portable ERE subset: no \b, \d, \s. A GNU-only escape silently fails the
#   awk half, which downgrades every BLOCK to REVIEW and turns the gate green.
file_rule() {
    local rule="$1" cat="$2" path_re="$3" trig="$4" req="$5" msg="$6"
    while IFS= read -r f; do
        [ -f "$f" ] || continue
        echo "$f" | grep -Eq "$path_re" || continue
        grep -Eq "$trig" "$f" || continue
        grep -Eq "$req" "$f" && continue
        local line sev
        line=$(grep -nE "$trig" "$f" | head -1 | cut -d: -f1)
        if awk -F'\t' -v F="$f" -v T="$trig" '$1 == F && $3 ~ T { found=1 } END { exit !found }' "$TMP/added.lines"; then
            sev="BLOCK"
        else
            sev="REVIEW"
        fi
        emit "$sev" "$cat" "$rule" "$f" "${line:-1}" "$msg" "$(sed -n "${line:-1}p" "$f" 2>/dev/null)"
    done < "$TMP/changed.files"
}

KT_RE='\.(kt|java)$'
WEB_RE='\.(ts|tsx|js|jsx|astro)$'
CTRL_RE='(controller|mcp/tools)/'
DATA_RE='(repository|service|controller)/'

# ---------------------------------------------------------------------------
# A01 — Broken Access Control
# ---------------------------------------------------------------------------

# Every controller needs @Secured. A public endpoint is an explicit, justified
# exception, so it must be argued in review, not defaulted into.
file_rule A01-no-secured A01 'controller/.*\.kt$' \
    '@(Get|Post|Put|Delete|Patch)([^A-Za-z]|$)' '@Secured' \
    'Controller declares HTTP endpoints but no @Secured anywhere in the file'

# An id in a request is untrusted input (CLAUDE.md, restated twice on purpose).
added_rule A01-findbyid REVIEW A01 "$CTRL_RE" \
    '\.findById\(' 'findById\((currentUser|authUser|userId|principal)' \
    'findById on a request id — resolve through AssetFilterService.canAccessAsset/getAccessibleAssetIds instead'

# SQL pre-filters are perf hints, never the auth boundary.
added_rule A01-sql-authz REVIEW A01 "$DATA_RE" \
    '(nativeQuery *= *true|@Query)' '-' \
    'Native/JPQL query touched — confirm authorization is still enforced in Kotlin, not by this WHERE clause'

# ---------------------------------------------------------------------------
# A02 — Cryptographic Failures
# ---------------------------------------------------------------------------

added_rule A02-token-storage BLOCK A02 "$WEB_RE" \
    '(localStorage|sessionStorage)\.(get|set)Item\([^)]*([Tt]oken|[Jj]wt|auth)' '-' \
    'JWT must stay in the HttpOnly secman_auth cookie — never JS-readable storage'

added_rule A02-weak-hash BLOCK A02 "$KT_RE" \
    'MessageDigest\.getInstance\("(MD5|SHA-1|SHA-256)"' 'McpAuthenticationService' \
    'Secrets hash with BCryptPasswordEncoder — the SHA-256 API-key path is legacy migration only'

# awk regexes are case-sensitive and the one-true-awk shipped on macOS has no
# interval expressions, so the cases are spelled out and "8 or more characters"
# is written as eight [^"] classes rather than {8,}.
added_rule A02-secret-lit BLOCK A02 '.' \
    '(password|Password|PASSWORD|secret|Secret|SECRET|apiKey|ApiKey|api_key|API_KEY|passwd|credential|Credential)[A-Za-z]*[ \t]*[:=][ \t]*"[^"$][^"][^"][^"][^"][^"][^"][^"][^"]*"' \
    '(\$\{|System\.getenv|getProperty|@Value|\*\*\*HIDDEN\*\*\*|process\.env|pass-cli|pass://|example|EXAMPLE|placeholder|<|password"|Password"|type|description)' \
    'Literal credential in source — resolve secrets from pass-cli/env, never inline'

# ---------------------------------------------------------------------------
# A03 — Injection
# ---------------------------------------------------------------------------

# "contains SQL keywords" is not enough on its own: "Failed to send mail from
# account: $id" matches it. Require a query-construction context as well.
QUERY_CTX='(@Query\(|createQuery\(|createNativeQuery\(|executeQuery\(|prepareStatement\(|nativeQuery|jdbcTemplate|"[ \t]*(SELECT|select|INSERT|insert|UPDATE|update|DELETE|delete)[ \t])'

added_rule_and A03-sql-interp BLOCK A03 "$DATA_RE" \
    "$QUERY_CTX" '\$\{?[A-Za-z_]' '-' \
    'String interpolation inside a query — bind with :params, map non-bindables through a closed allowlist'

added_rule_and A03-sql-concat BLOCK A03 "$DATA_RE" \
    "$QUERY_CTX" '"[ \t]*\+[ \t]*[A-Za-z_]' '-' \
    'String concatenation into a query — bind with :params instead'

added_rule A03-html BLOCK A03 "$WEB_RE" \
    '(dangerouslySetInnerHTML|\.innerHTML[ \t]*=|insertAdjacentHTML\()' 'DOMPurify\.sanitize' \
    'Unsanitized HTML sink — call DOMPurify.sanitize at the assignment site (see RichContent.tsx)'

added_rule A03-excel REVIEW A03 "$KT_RE" \
    'setCellValue\([^")]' 'ExcelSanitizer\.sanitize' \
    'Excel cell from a variable — user-controlled cells must go through ExcelSanitizer.sanitize (formula/DDE injection)'

added_rule A03-shell BLOCK A03 "$KT_RE" \
    '(ProcessBuilder\(|Runtime\.getRuntime\(\)\.exec\()[^)]*\$' '-' \
    'Interpolated string in a shell/exec call — pass an argv array, never a built command string'

added_rule A03-shell-sh REVIEW A03 '\.sh$' \
    '(eval |curl [^|]*\$[A-Za-z_]|`[^`]*\$)' '-' \
    'Shell expansion of a variable into a command — quote it and prefer argv over eval'

# ---------------------------------------------------------------------------
# A04 — Insecure Design
# ---------------------------------------------------------------------------

added_rule A04-unbounded REVIEW A04 '(controller|service)/' \
    '\.findAll\(\)' '-' \
    'Unbounded findAll() — page at the query (findBy...In(ids, pageable)); this pattern OOMed get_vulnerabilities on 1.1M rows'

added_rule A04-todo-auth REVIEW A04 "$KT_RE" \
    '@Secured\(SecurityRule\.IS_AUTHENTICATED\)' '-' \
    'IS_AUTHENTICATED is the widest authenticated scope — confirm the narrowest role that works was considered'

# ---------------------------------------------------------------------------
# A05 — Security Misconfiguration
# ---------------------------------------------------------------------------

added_rule A05-cors-wildcard BLOCK A05 '.' \
    '(allowedOrigins|allowed-origins|Access-Control-Allow-Origin)[^A-Za-z]*("|'"'"')?\*' '-' \
    'Wildcard CORS origin — use an explicit allowlist, never * with credentials'

added_rule A05-headers REVIEW A05 'SecurityHeadersFilter' \
    '(Content-Security-Policy|Strict-Transport-Security|X-Frame-Options|unsafe-eval|unsafe-inline)' '-' \
    'Security header policy changed — weakening CSP/HSTS/X-Frame-Options requires changing the feature instead'

added_rule A05-error-leak REVIEW A05 'controller/' \
    '(stackTraceToString\(\)|printStackTrace\(\))' '-' \
    'Do not surface stack traces to clients — generic message out, detail to the server log (see ValidationExceptionHandler)'

# ---------------------------------------------------------------------------
# A06 — Vulnerable and Outdated Components
# ---------------------------------------------------------------------------

added_rule A06-floating-gradle BLOCK A06 '\.(gradle|gradle\.kts|toml)$' \
    ':[0-9][^"]*\+"|version *= *"(latest|\+)' '-' \
    'Floating dependency version — pin an exact version'

added_rule A06-floating-npm BLOCK A06 'package\.json$' \
    '"[^"]+" *: *"(\*|latest|>=)' '-' \
    'Floating npm version — pin exactly and keep package-lock.json in step (npm ci is the gate)'

added_rule A06-clinotify-dep BLOCK A06 'src/clinotify/' \
    '^[ \t]*(import|from) (requests|httpx|urllib3|yaml|pydantic|boto3|click|rich|dotenv|numpy|pandas)' '-' \
    'src/clinotify is stdlib-only by contract — a third-party import breaks its deployment'

# ---------------------------------------------------------------------------
# A07 — Identification and Authentication Failures
# ---------------------------------------------------------------------------

added_rule A07-anonymous REVIEW A07 "$KT_RE" \
    '(IS_ANONYMOUS|isAnonymous\(\))' '-' \
    'Public endpoint — must be an explicit, justified exception, never a default'

file_rule A07-header-auth A07 "$KT_RE" \
    'X-MCP-User-Email' '(McpAuthenticationService|X-MCP-API-Key|apiKey|ApiKey)' \
    'X-MCP-User-Email identifies a delegated user, it is not a credential — it must sit behind a verified API key'

added_rule A07-cookie-flags BLOCK A07 '.' \
    '(httpOnly|HttpOnly|httponly)[ \t]*[:=][ \t]*false' '-' \
    'Never loosen HttpOnly on the auth cookie'

# ---------------------------------------------------------------------------
# A08 — Software and Data Integrity Failures
# ---------------------------------------------------------------------------

file_rule A08-xxe A08 "$KT_RE" \
    '(DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|SchemaFactory)' 'disallow-doctype-decl' \
    'XML parser without the XXE block — copy the four setFeature calls from NmapParserService'

added_rule A08-polymorphic BLOCK A08 "$KT_RE" \
    '(activateDefaultTyping|enableDefaultTyping)' '-' \
    'Polymorphic deserialization of untrusted input — parse into an explicit DTO'

file_rule A08-zip A08 "$KT_RE" \
    '(ZipInputStream|ZipFile|ZipEntry|TarArchive)' '(\.\.|zipSlip|canonicalPath|normalize\(\))' \
    'Archive extraction without a path-traversal guard — reject entries containing .. and bound the decompressed size'

# Trigger on the parameter, not the import: a vestigial import in a controller
# that no longer accepts uploads is not an upload endpoint.
file_rule A08-upload A08 'controller/.*\.kt$' \
    ': CompletedFileUpload' '(validateFile|validateUpload|MAX_FILE_SIZE|MAX_UPLOAD|\.size >|\.size ==)' \
    'Upload endpoint without size/extension/content-type/empty validation before parsing (see ImportController.validateFile)'

added_rule A08-remote-exec BLOCK A08 '.' \
    '(eval\(await |eval\(fetch|new Function\()' '-' \
    'Never fetch code or config at runtime and evaluate it'

# ---------------------------------------------------------------------------
# A09 — Security Logging and Monitoring Failures
# ---------------------------------------------------------------------------

added_rule A09-empty-catch BLOCK A09 '.' \
    'catch *\([^)]*\) *\{ *\}' '-' \
    'Silently swallowed exception — a suppressed security-relevant failure is itself a monitoring failure'

# Naming a secret in a log message is fine ("using the default password is
# insecure"); logging its VALUE is not. So the second regex looks for the value
# reaching the call — interpolated, or passed as an argument — not for the word.
added_rule_and A09-secret-log BLOCK A09 '.' \
    '(log\.|logger\.|println|console\.(log|error|warn|debug))' \
    '(\$\{?[A-Za-z_.]*([Pp]assword|[Tt]oken|[Ss]ecret|[Aa]pi[Kk]ey|[Cc]ookie)|[(,][ \t]*[A-Za-z_.]*([Pp]assword|[Tt]oken|[Ss]ecret|[Aa]pi[Kk]ey|[Cc]ookie)[A-Za-z]*\))' \
    '(Configured|HIDDEN|\.length|len\(|\.size|isNullOrBlank|isNotBlank|!= null|masked|Masked|expires|Expires|prefix|Prefix|hashOf|\.take\()' \
    'Never log a password, token, cookie or API key value — logger.debug counts, it runs in dev with real secrets'

added_rule A09-crlf REVIEW A09 "$KT_RE" \
    '(log\.|logger\.)(info|warn|error|debug)\([^)]*\$\{?(request|filename|name|email|input|param|query|header)' '(sanitize|InputValidation|replace)' \
    'User input into a log line — strip CR/LF first (log forging), see InputValidationService'

# ---------------------------------------------------------------------------
# A10 — Server-Side Request Forgery
# ---------------------------------------------------------------------------

added_rule A10-outbound REVIEW A10 "$KT_RE" \
    '(URI\.create\(|URI\(|URL\(|HttpRequest\.(GET|POST|PUT|DELETE)\()[^")]*[a-z]' \
    '(validateWebhookUrl|validateBotToken|"https://|"http://|URI\(webhook|URI\(base)' \
    'Outbound URL from a variable — validate https scheme + host allowlist and reject loopback/link-local/RFC-1918/169.254.169.254 (see SlackClient.validateWebhookUrl)'

# ---------------------------------------------------------------------------
# MCP registration — A01, and the one that fails OPEN
# ---------------------------------------------------------------------------
#
# A missing CALLING entry fails closed and looks like a bug someone will chase.
# A missing guard fails OPEN and looks like nothing at all, which is why this is
# checked mechanically rather than trusted to review.

PERMS_FILE="src/backendng/src/main/kotlin/com/secman/mcp/McpToolPermissions.kt"
CATS_FILE="src/backendng/src/main/kotlin/com/secman/mcp/ToolCategories.kt"
if [ -f "$PERMS_FILE" ]; then
    # The two maps are checked separately rather than by counting names in the
    # whole file: "listed twice in LISTING" and "listed once in each" are very
    # different states and only the second one is correct. CALLING is also fed
    # by a fold over ToolCategories.CATEGORY_PERMISSIONS, so that file counts
    # towards the CALLING side.
    awk '/val LISTING/,/val CALLING/' "$PERMS_FILE" > "$TMP/mcp_listing"
    awk '/val CALLING/,0' "$PERMS_FILE" > "$TMP/mcp_calling"
    [ -f "$CATS_FILE" ] && cat "$CATS_FILE" >> "$TMP/mcp_calling"
    while IFS= read -r f; do
        case "$f" in
            */mcp/tools/*.kt) ;;
            *) continue ;;
        esac
        [ -f "$f" ] || continue
        tool=$(grep -Eo 'override val name(: String)? *= *"[a-z0-9_]+"' "$f" | grep -Eo '"[a-z0-9_]+"' | tr -d '"' | head -1)
        [ -n "$tool" ] || continue
        missing=""
        grep -q "\"$tool\"" "$TMP/mcp_listing" || missing="LISTING"
        grep -q "\"$tool\"" "$TMP/mcp_calling" || missing="${missing:+$missing and }CALLING"
        if [ -n "$missing" ]; then
            line=$(grep -nE 'override val name' "$f" | head -1 | cut -d: -f1)
            emit BLOCK A01 A01-mcp-perms "$f" "${line:-1}" \
                "MCP tool '$tool' is missing from McpToolPermissions $missing — absent from CALLING means tools/call is denied for delegated callers" ""
        fi
        # McpToolGuards helpers are the preferred form, but a hand-written
        # `context.isAdmin` / `hasDelegation()` check in execute() is the same
        # boundary and predates the helpers — accept both, flag neither-of.
        if ! grep -Eq '(requireDelegation|requireAnyRole|requireDelegatedRole|requireAdmin|context\.isAdmin|hasDelegation\(\)|delegatedUserRoles)' "$f"; then
            line=$(grep -nE 'fun execute' "$f" | head -1 | cut -d: -f1)
            emit BLOCK A01 A01-mcp-guard "$f" "${line:-1}" \
                "MCP tool '$tool' calls no McpToolGuards check — a missing guard fails OPEN and looks like nothing" ""
        fi
    done < "$TMP/changed.files"
fi

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

BLOCKS=$(grep -c '^BLOCK|' "$TMP/findings" 2>/dev/null || true)
REVIEWS=$(grep -c '^REVIEW|' "$TMP/findings" 2>/dev/null || true)
BLOCKS=${BLOCKS:-0}; REVIEWS=${REVIEWS:-0}

if [ "$MODE" = "all" ]; then
    echo "owasp-check: whole-repo audit — $CHANGED_COUNT source file(s)"
else
    echo "owasp-check: $CHANGED_COUNT changed file(s), $ADDED_COUNT added line(s) vs $BASE ($(cat "$TMP/base_sha" 2>/dev/null | cut -c1-8))"
fi

print_group() { # print_group SEV LABEL
    grep "^$1|" "$TMP/findings" 2>/dev/null | sort -t'|' -k2,2 -k4,4 | while IFS='|' read -r sev cat rule path line msg text; do
        printf '  [%s] %s %s\n      %s:%s\n      %s\n' "$2" "$cat" "$rule" "$path" "$line" "$msg"
        if [ "$VERBOSE" -eq 1 ] && [ -n "$text" ]; then
            printf '      > %s\n' "$(echo "$text" | cut -c1-160)"
        fi
    done
}

if [ "$BLOCKS" -gt 0 ]; then
    echo
    echo "BLOCK — introduced by this change, fix before the change is complete:"
    print_group BLOCK BLOCK
fi

if [ "$REVIEWS" -gt 0 ]; then
    echo
    echo "REVIEW — look, decide, and say what you decided:"
    print_group REVIEW REVIEW
fi

echo
echo "owasp-check: exempt from scanning (self-referential by design): scripts/owasp-check.sh, scripts/test/owasp-check-test.sh"

if [ "$BLOCKS" -eq 0 ] && [ "$REVIEWS" -eq 0 ]; then
    echo "owasp-check: OK — no static findings"
    echo "Reminder: the semantic half (owner-scoping, deny-by-default roles, business"
    echo "invariants) is not checkable here. Re-read the diff against A01-A10."
    exit 0
fi

echo "owasp-check: $BLOCKS block, $REVIEWS review"
if [ "$BLOCKS" -gt 0 ]; then
    exit 1
fi
if [ "$STRICT" -eq 1 ] && [ "$REVIEWS" -gt 0 ]; then
    echo "owasp-check: --strict — REVIEW findings treated as failures"
    exit 1
fi
exit 0
