#!/usr/bin/env bash
# Shared plumbing for the CYBERDEF ticket scripts. Source it; do not run it.
#
# Environment (normally loaded from Proton Pass through ./secmanpp.env):
#   SECMAN_BACKEND_URL     SecMan base URL, e.g. https://secman.example.com
#   SECMAN_MCP_KEY         MCP API key with delegation enabled
#   SECMAN_CYBERDEF_EMAIL  delegated user (must hold CYBERDEF or ADMIN for the
#                          defender scripts); falls back to SECMAN_ADMIN_EMAIL
#   SECMAN_INSECURE=true   skip TLS verification (local development only)
#
# Exit codes used by every script:
#   0 success   1 runtime or MCP error   2 usage error   3 ticket(s) rejected

set -euo pipefail

CYBERDEF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$CYBERDEF_DIR/../.." && pwd)"
PRINT_REQUEST=false
DELEGATE_EMAIL=""

die() { echo "$*" >&2; exit 1; }
usage_error() { echo "$*" >&2; exit 2; }

# Fails early with a clear message instead of a mid-run "command not found".
require_tools() {
    local tool
    for tool in curl jq; do
        command -v "$tool" >/dev/null || die "Required command not found: $tool"
    done
}

# Re-executes the calling script under `pass-cli run` when credentials are missing.
# Printing a request needs no credentials, so it never re-executes.
# Usage: ensure_credentials "$SCRIPT_PATH" "${ORIGINAL_ARGS[@]}"
ensure_credentials() {
    local script="$1"; shift
    [[ "$PRINT_REQUEST" == true ]] && return 0
    if [[ -n "${SECMAN_BACKEND_URL:-}" && -n "${SECMAN_MCP_KEY:-}" ]]; then
        return 0
    fi
    [[ -z "${CYBERDEF_PASS_REEXEC:-}" ]] || die "SECMAN_BACKEND_URL and SECMAN_MCP_KEY are still unset after pass-cli"
    command -v pass-cli >/dev/null || die "Set SECMAN_BACKEND_URL and SECMAN_MCP_KEY, or install pass-cli"
    [[ -f "$REPO_ROOT/secmanpp.env" ]] || die "Missing Proton Pass environment file: $REPO_ROOT/secmanpp.env"
    export CYBERDEF_PASS_REEXEC=1
    exec pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- "$script" "$@"
}

# Who the MCP call acts as: --as wins, then SECMAN_CYBERDEF_EMAIL, then SECMAN_ADMIN_EMAIL.
delegate_email() {
    local email="${DELEGATE_EMAIL:-${SECMAN_CYBERDEF_EMAIL:-${SECMAN_ADMIN_EMAIL:-}}}"
    if [[ -z "$email" && "$PRINT_REQUEST" == true ]]; then
        email="delegated-user@example.invalid"
    fi
    [[ -n "$email" ]] || usage_error "No delegated user: pass --as EMAIL or set SECMAN_CYBERDEF_EMAIL"
    printf '%s' "$email"
}

# Comma- or space-separated list -> JSON array of trimmed, non-empty strings.
json_list() {
    jq -cn --arg raw "$1" '$raw | split("[,[:space:]]+"; null) | map(select(length > 0))'
}

# Local syntax check, same pattern as the other repository scripts; SecMan re-validates.
valid_email() {
    [[ "$1" =~ ^[^[:space:],\;:\<\>\"\\]+@[^[:space:],\;:\<\>\"\\]+\.[^[:space:],\;:\<\>\"\\]+$ ]]
}

# Checks every address of a JSON array locally before anything is sent.
check_recipients() {
    local address
    while IFS= read -r address; do
        valid_email "$address" || usage_error "Not a valid recipient address: $address"
    done < <(printf '%s' "$1" | jq -r '.[]')
    (( $(printf '%s' "$1" | jq 'length') <= 20 )) || usage_error "At most 20 recipients"
}

# Builds the JSON-RPC body for one tool call.
mcp_request() {
    printf '%s' "$2" | jq -c --arg tool "$1" --arg id "cyberdef-${RANDOM}${RANDOM}" \
        '{jsonrpc:"2.0",id:$id,method:"tools/call",params:{name:$tool,arguments:.}}'
}

# Prints the request mcp_call would send (headers included, key masked). Call it directly,
# not inside $(...), then exit: `[[ $PRINT_REQUEST == true ]] && { print_request t "$a"; exit 0; }`
print_request() {
    jq -n --argjson body "$(mcp_request "$1" "$2")" --arg url "${SECMAN_BACKEND_URL:-https://secman.example.invalid}/mcp" \
        --arg user "$(delegate_email)" \
        '{method:"POST",url:$url,headers:{"X-MCP-API-Key":"***","X-MCP-User-Email":$user},body:$body}'
}

# Calls one MCP tool and prints its JSON result on stdout.
# On a tool error it prints "<tool>: <CODE>: <message>" to stderr and returns 1.
mcp_call() {
    local tool="$1" arguments="$2" request response email
    [[ "$PRINT_REQUEST" != true ]] || die "internal: mcp_call in --print-request mode"
    request="$(mcp_request "$tool" "$arguments")"
    email="$(delegate_email)"
    local tls=()
    case "$(printf '%s' "${SECMAN_INSECURE:-false}" | tr '[:upper:]' '[:lower:]')" in
        true|1|yes) tls=(-k) ;;
    esac
    # The key is handed to curl through a process-substitution header file, never on argv.
    response="$(printf '%s' "$request" | curl "${tls[@]}" -sS --fail-with-body -X POST "${SECMAN_BACKEND_URL%/}/mcp" \
        -H 'Content-Type: application/json' \
        -H @<(printf 'X-MCP-API-Key: %s\n' "$SECMAN_MCP_KEY") \
        -H "X-MCP-User-Email: ${email}" \
        --data-binary @-)" || { echo "${tool}: HTTP request failed" >&2; return 1; }
    if ! printf '%s' "$response" | jq -e '.error == null and .result.isError != true' >/dev/null 2>&1; then
        # Ticket tools lead the message with their stable code, e.g. "VERSION_CONFLICT: ...".
        printf '%s' "$response" | jq -r --arg tool "$tool" \
            '"\($tool): \(.error.message // .result.content[0].text // "unknown MCP error")"' >&2 \
            || echo "${tool}: unreadable MCP response" >&2
        return 1
    fi
    printf '%s' "$response" | jq -c '
        if (.result.content | type) == "array" then (.result.content[0].text | fromjson) else .result.content end'
}
