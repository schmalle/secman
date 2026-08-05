#!/usr/bin/env bash
#
# query-vulnerabilities-mcp.sh
#
# Query all *current* vulnerabilities that a given secman user can see, via
# the secman MCP (Model Context Protocol) JSON-RPC API.
#
# This is a standalone client script: it talks to a secman instance's
# `POST {URL}/mcp` JSON-RPC endpoint using an MCP API key. The API key
# authenticates the *caller* (a service identity); the delegated user
# (-e/--user) determines *whose* visibility scope is applied. Results are
# therefore governed entirely by secman's server-side access control for the
# delegated user, not by the caller or the key's own role:
#   - ADMIN or SECCHAMPION delegated users see every current vulnerability.
#   - Every other role only sees vulnerabilities on assets that user has
#     access to (workgroup membership, asset ownership, AWS/AD-domain
#     mapping, etc. - secman's "Unified Asset Access" rules).
# The MCP API key itself must also carry the VULNERABILITIES_READ
# permission, or the call will fail with a permission error regardless of
# the delegated user's role.
#
# Under the hood this calls the `get_all_accessible_vulnerabilities` MCP
# tool - a single unpaginated call that returns every current vulnerability
# across every asset the delegated user can access, up to --limit rows
# (server-side hard cap: 20000).
#
# Usage:
#   query-vulnerabilities-mcp.sh -U URL -k API_KEY -e USER_EMAIL [OPTIONS]
#
# Required (each may be given as a flag, or via a fallback environment
# variable - useful for keeping the API key out of shell history):
#   -U, --url URL         Base URL of the secman instance, e.g.
#                          https://secman.example.com
#                          (env fallback: SECMAN_BASE_URL)
#   -k, --api-key KEY     MCP API key with the VULNERABILITIES_READ
#                          permission (env fallback: SECMAN_MCP_KEY)
#   -e, --user EMAIL      Email of the secman user to query on behalf of
#                          (env fallback: SECMAN_USER_EMAIL)
#
# Optional:
#       --severity LIST    Comma-separated severities to filter by:
#                           CRITICAL,HIGH,MEDIUM,LOW (default: all)
#       --include-excepted Include vulnerabilities covered by an active
#                           exception (default: excluded, matching the
#                           secman UI's default view)
#       --limit N          Max rows to fetch, 1-20000 (default: 20000)
#       --format FORMAT    Output format: json (default) or table
#       --raw              Print compact JSON with no pretty-printing
#                           (implies --format json)
#   -o, --output FILE      Write output to FILE instead of stdout
#       --insecure          Skip TLS certificate verification (curl -k) -
#                            only use this against instances with
#                            self-signed/internal certs you trust
#   -v, --verbose            Print the outgoing request (API key redacted)
#                             and extra diagnostics to stderr
#   -h, --help                Show this help and exit
#
# Examples:
#   # Plain query, pretty JSON to stdout
#   ./scripts/query-vulnerabilities-mcp.sh \
#       -U https://secman.example.com -k sk-xxxx -e alice@example.com
#
#   # Only Critical/High, human-readable table
#   ./scripts/query-vulnerabilities-mcp.sh \
#       -U https://secman.example.com -k sk-xxxx -e alice@example.com \
#       --severity CRITICAL,HIGH --format table
#
#   # Keep the API key out of shell history, write result to a file
#   export SECMAN_MCP_KEY=sk-xxxx
#   ./scripts/query-vulnerabilities-mcp.sh \
#       -U https://secman.example.com -e alice@example.com -o vulns.json
#
# Exit codes:
#   0  success
#   1  usage error (missing or invalid arguments)
#   2  missing dependency (curl or jq not found)
#   3  request failed (network, HTTP, JSON-RPC, or MCP tool-level error)
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults / globals
# ---------------------------------------------------------------------------
SCRIPT_NAME="$(basename "$0")"

URL="${SECMAN_BASE_URL:-}"
API_KEY="${SECMAN_MCP_KEY:-}"
USER_EMAIL="${SECMAN_USER_EMAIL:-}"
SEVERITY=""
INCLUDE_EXCEPTED="false"
LIMIT="20000"
FORMAT="json"
RAW="false"
OUTPUT_FILE=""
INSECURE="false"
VERBOSE="false"

VALID_SEVERITIES="CRITICAL HIGH MEDIUM LOW"

# ---------------------------------------------------------------------------
# Output helpers (all diagnostics go to stderr so stdout stays clean/pipeable)
# ---------------------------------------------------------------------------
if [[ -t 2 ]]; then
    COLOR_RED=$'\033[31m'; COLOR_YELLOW=$'\033[33m'; COLOR_BLUE=$'\033[34m'; COLOR_RESET=$'\033[0m'
else
    COLOR_RED=""; COLOR_YELLOW=""; COLOR_BLUE=""; COLOR_RESET=""
fi

log_info()  { printf '%s[INFO]%s  %s\n'  "$COLOR_BLUE" "$COLOR_RESET" "$*" >&2; }
log_warn()  { printf '%s[WARN]%s  %s\n'  "$COLOR_YELLOW" "$COLOR_RESET" "$*" >&2; }
log_error() { printf '%s[ERROR]%s %s\n'  "$COLOR_RED" "$COLOR_RESET" "$*" >&2; }
log_verbose() { [[ "$VERBOSE" == "true" ]] && printf '%s[DEBUG]%s %s\n' "$COLOR_BLUE" "$COLOR_RESET" "$*" >&2; return 0; }

print_usage() {
    sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        -U|--url)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            URL="$2"; shift 2 ;;
        -k|--api-key)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            API_KEY="$2"; shift 2 ;;
        -e|--user)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            USER_EMAIL="$2"; shift 2 ;;
        --severity)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            SEVERITY="$2"; shift 2 ;;
        --include-excepted)
            INCLUDE_EXCEPTED="true"; shift ;;
        --limit)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            LIMIT="$2"; shift 2 ;;
        --format)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            FORMAT="$2"; shift 2 ;;
        --raw)
            RAW="true"; FORMAT="json"; shift ;;
        -o|--output)
            [[ $# -ge 2 ]] || { log_error "$1 requires a value"; exit 1; }
            OUTPUT_FILE="$2"; shift 2 ;;
        --insecure)
            INSECURE="true"; shift ;;
        -v|--verbose)
            VERBOSE="true"; shift ;;
        -h|--help)
            print_usage; exit 0 ;;
        *)
            log_error "Unknown argument: $1"
            print_usage
            exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------
check_dependencies() {
    local missing=()
    command -v curl >/dev/null 2>&1 || missing+=("curl")
    command -v jq   >/dev/null 2>&1 || missing+=("jq")
    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing required dependencies: ${missing[*]}"
        exit 2
    fi
}
check_dependencies

# ---------------------------------------------------------------------------
# Validate required arguments
# ---------------------------------------------------------------------------
if [[ -z "$URL" || -z "$API_KEY" || -z "$USER_EMAIL" ]]; then
    log_error "Missing required argument(s): $( [[ -z "$URL" ]] && echo -n '-U/--url ' )$( [[ -z "$API_KEY" ]] && echo -n '-k/--api-key ' )$( [[ -z "$USER_EMAIL" ]] && echo -n '-e/--user ' )"
    print_usage
    exit 1
fi

# Strip trailing slash from URL
URL="${URL%/}"

if [[ ! "$FORMAT" =~ ^(json|table)$ ]]; then
    log_error "Invalid --format '$FORMAT': must be 'json' or 'table'"
    exit 1
fi

if [[ ! "$LIMIT" =~ ^[0-9]+$ ]] || [[ "$LIMIT" -lt 1 ]] || [[ "$LIMIT" -gt 20000 ]]; then
    log_error "Invalid --limit '$LIMIT': must be an integer between 1 and 20000"
    exit 1
fi

SEVERITY_JSON="[]"
if [[ -n "$SEVERITY" ]]; then
    IFS=',' read -ra SEVERITY_PARTS <<< "$SEVERITY"
    for s in "${SEVERITY_PARTS[@]}"; do
        s_upper="$(echo "$s" | tr '[:lower:]' '[:upper:]' | xargs)"
        if [[ ! " $VALID_SEVERITIES " == *" $s_upper "* ]]; then
            log_error "Invalid --severity value '$s': must be one of CRITICAL, HIGH, MEDIUM, LOW"
            exit 1
        fi
    done
    SEVERITY_JSON="$(printf '%s\n' "${SEVERITY_PARTS[@]}" | tr '[:lower:]' '[:upper:]' | xargs -n1 | jq -R . | jq -s .)"
fi

# ---------------------------------------------------------------------------
# Build the MCP JSON-RPC request
# ---------------------------------------------------------------------------
REQUEST_ID="query-vulns-$(date +%s 2>/dev/null || echo 0)-$$"

ARGUMENTS_JSON="$(jq -nc \
    --argjson severity "$SEVERITY_JSON" \
    --argjson includeExcepted "$INCLUDE_EXCEPTED" \
    --argjson limit "$LIMIT" \
    '{severity: $severity, includeExcepted: $includeExcepted, limit: $limit}
     | if (.severity | length) == 0 then del(.severity) else . end')"

REQUEST_BODY="$(jq -nc \
    --arg id "$REQUEST_ID" \
    --arg name "get_all_accessible_vulnerabilities" \
    --argjson arguments "$ARGUMENTS_JSON" \
    '{jsonrpc: "2.0", id: $id, method: "tools/call", params: {name: $name, arguments: $arguments}}')"

mask_key() {
    local key="$1"
    if [[ ${#key} -gt 8 ]]; then
        printf '%s****%s' "${key:0:4}" "${key: -4}"
    else
        printf '****'
    fi
}

log_verbose "POST ${URL}/mcp"
log_verbose "X-MCP-API-Key: $(mask_key "$API_KEY")"
log_verbose "X-MCP-User-Email: ${USER_EMAIL}"
log_verbose "Body: ${REQUEST_BODY}"

# ---------------------------------------------------------------------------
# Call the MCP endpoint
# ---------------------------------------------------------------------------
CURL_OPTS=(-sS -w '\n%{http_code}' -X POST "${URL}/mcp"
    -H 'Content-Type: application/json'
    -H "X-MCP-API-Key: ${API_KEY}"
    -H "X-MCP-User-Email: ${USER_EMAIL}"
    --data "${REQUEST_BODY}")
[[ "$INSECURE" == "true" ]] && CURL_OPTS+=(-k)

if ! RAW_RESPONSE="$(curl "${CURL_OPTS[@]}")"; then
    log_error "Request to ${URL}/mcp failed (network error or connection refused)"
    exit 3
fi

HTTP_STATUS="$(echo "$RAW_RESPONSE" | tail -n1)"
RESPONSE_BODY="$(echo "$RAW_RESPONSE" | sed '$d')"

log_verbose "HTTP status: ${HTTP_STATUS}"
log_verbose "Response: ${RESPONSE_BODY}"

if [[ ! "$HTTP_STATUS" =~ ^2[0-9]{2}$ ]]; then
    log_error "secman returned HTTP ${HTTP_STATUS}"
    log_error "Response body: ${RESPONSE_BODY}"
    exit 3
fi

if ! echo "$RESPONSE_BODY" | jq -e . >/dev/null 2>&1; then
    log_error "secman did not return valid JSON:"
    log_error "$RESPONSE_BODY"
    exit 3
fi

RPC_ERROR="$(echo "$RESPONSE_BODY" | jq -r '.error.message // empty')"
if [[ -n "$RPC_ERROR" ]]; then
    RPC_ERROR_CODE="$(echo "$RESPONSE_BODY" | jq -r '.error.code // empty')"
    log_error "MCP request failed (code ${RPC_ERROR_CODE}): ${RPC_ERROR}"
    exit 3
fi

IS_TOOL_ERROR="$(echo "$RESPONSE_BODY" | jq -r '.result.isError // false')"
TOOL_TEXT="$(echo "$RESPONSE_BODY" | jq -r '.result.content[0].text // empty')"

if [[ "$IS_TOOL_ERROR" == "true" ]]; then
    log_error "MCP tool 'get_all_accessible_vulnerabilities' returned an error: ${TOOL_TEXT}"
    exit 3
fi

if [[ -z "$TOOL_TEXT" ]]; then
    log_error "Unexpected MCP response shape - no result.content[0].text found:"
    log_error "$RESPONSE_BODY"
    exit 3
fi

if ! PAYLOAD="$(echo "$TOOL_TEXT" | jq -e .)"; then
    log_error "Could not parse tool payload as JSON: ${TOOL_TEXT}"
    exit 3
fi

# ---------------------------------------------------------------------------
# Report result stats / truncation warning (stderr only, keeps stdout clean)
# ---------------------------------------------------------------------------
TOTAL="$(echo "$PAYLOAD" | jq -r '.total // 0')"
RETURNED="$(echo "$PAYLOAD" | jq -r '.returned // (.vulnerabilities | length)')"
TRUNCATED="$(echo "$PAYLOAD" | jq -r '.truncated // false')"

log_info "${RETURNED} vulnerabilit$( [[ "$RETURNED" == "1" ]] && echo "y" || echo "ies" ) returned (of ${TOTAL} total) for ${USER_EMAIL}"

if [[ "$TRUNCATED" == "true" ]]; then
    log_warn "Result was truncated at --limit ${LIMIT}. Narrow the query with --severity or raise --limit (max 20000) to see the rest."
fi

# ---------------------------------------------------------------------------
# Emit output
# ---------------------------------------------------------------------------
emit() {
    if [[ -n "$OUTPUT_FILE" ]]; then
        cat > "$OUTPUT_FILE"
        log_info "Output written to ${OUTPUT_FILE}"
    else
        cat
    fi
}

case "$FORMAT" in
    json)
        if [[ "$RAW" == "true" ]]; then
            echo "$PAYLOAD" | jq -c '.vulnerabilities' | emit
        else
            echo "$PAYLOAD" | jq '.vulnerabilities' | emit
        fi
        ;;
    table)
        {
            printf '%-8s %-30s %-20s %-10s %-10s %-25s\n' "ID" "ASSET" "CVE" "SEVERITY" "DAYS_OPEN" "SCAN_TIMESTAMP"
            echo "$PAYLOAD" | jq -r '
                .vulnerabilities[] |
                [
                    (.id // "-" | tostring),
                    (.asset.name // "-" | tostring),
                    (.vulnerabilityId // "-" | tostring),
                    (.cvssSeverity // "-" | tostring),
                    (.daysOpen // "-" | tostring),
                    (.scanTimestamp // "-" | tostring)
                ] | @tsv' | awk -F'\t' '{printf "%-8s %-30s %-20s %-10s %-10s %-25s\n", $1, $2, $3, $4, $5, $6}'
        } | emit
        ;;
esac
