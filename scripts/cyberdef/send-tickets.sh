#!/usr/bin/env bash
# Submit many CYBERDEF tickets from a JSON file, 100 per MCP call.
set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")
# shellcheck source=lib.sh
source "$(dirname "$SCRIPT_PATH")/lib.sh"

# --help text; also printed on a usage error.
usage() {
    cat <<'EOF'
Usage: scripts/cyberdef/send-tickets.sh --file tickets.json [options]

The file holds a JSON array of ticket objects, or {"tickets": [...]}, with the same fields
as send-ticket.sh --file (see examples/tickets.json). Give every ticket an
externalReference: a rerun after a partial failure then reports DUPLICATE for the tickets
already created instead of creating them twice.

  --file FILE          tickets to submit                                      (required)
  --to ADDR[,ADDR]     mail these addresses instead of the account owners, for every
                       ticket that does not set its own "recipients" (repeatable, max 20)
  --dry-run            let SecMan validate and resolve every ticket; store and send nothing
  --print-request      print the first MCP request and exit; no network, no credentials
  --as EMAIL           delegated user (default: SECMAN_CYBERDEF_EMAIL, then SECMAN_ADMIN_EMAIL)
  -h, --help

Output: one JSON line per ticket (index into the file, outcome, ticketKey or errors), then a
summary line on stderr. Exit codes: 0 all accepted, 1 error, 2 usage, 3 some rejected.
EOF
}

FILE="" RECIPIENTS=() DRY_RUN=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --file) FILE="${2:?--file needs a value}"; shift 2 ;;
        --to) RECIPIENTS+=("${2:?--to needs a value}"); shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        --print-request) PRINT_REQUEST=true; shift ;;
        --as) DELEGATE_EMAIL="${2:?--as needs a value}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done
[[ -n "$FILE" ]] || { usage >&2; exit 2; }

require_tools
ensure_credentials "$SCRIPT_PATH" "${ORIGINAL_ARGS[@]}"

TICKETS="$(jq -ce 'if type == "array" then . elif type == "object" and (.tickets | type) == "array" then .tickets
    else error("expected an array of tickets") end' -- "$FILE")" || usage_error "$FILE: expected a JSON array of tickets"
TOTAL="$(jq 'length' <<<"$TICKETS")"
(( TOTAL > 0 )) || usage_error "$FILE contains no tickets"

RECIPIENT_JSON="$(json_list "${RECIPIENTS[*]+"${RECIPIENTS[*]}"}")"
[[ "$RECIPIENT_JSON" == "[]" ]] || check_recipients "$RECIPIENT_JSON"

created=0 duplicates=0 rejected=0
for (( offset = 0; offset < TOTAL; offset += 100 )); do
    ARGUMENTS="$(jq -c --argjson from "$offset" --argjson dry "$DRY_RUN" --argjson to "$RECIPIENT_JSON" '
        {tickets: .[$from:$from + 100]}
        + (if $dry then {dryRun: true} else {} end)
        + (if ($to | length) > 0 then {recipients: $to} else {} end)' <<<"$TICKETS")"
    [[ "$PRINT_REQUEST" != true ]] || { print_request create_cyberdef_tickets "$ARGUMENTS"; exit 0; }
    RESULT="$(mcp_call create_cyberdef_tickets "$ARGUMENTS")" || {
        echo "Batch starting at ticket $offset failed; rerun to continue (externalReference makes it safe)" >&2
        exit 1
    }
    # Report positions in the file, not in this chunk.
    jq -c --argjson from "$offset" '.results[] | .index += $from' <<<"$RESULT"
    created=$(( created + $(jq '.created' <<<"$RESULT") ))
    duplicates=$(( duplicates + $(jq '.duplicates' <<<"$RESULT") ))
    rejected=$(( rejected + $(jq '.rejected' <<<"$RESULT") ))
done

label="created"
[[ "$DRY_RUN" == true ]] && label="would be created"
echo "$TOTAL ticket(s): $created $label, $duplicates duplicate, $rejected rejected" >&2
(( rejected == 0 )) || exit 3
