#!/usr/bin/env bash
# Submit one CYBERDEF ticket about an AWS account through SecMan MCP.
set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")
# shellcheck source=lib.sh
source "$(dirname "$SCRIPT_PATH")/lib.sh"

# --help text; also printed on a usage error.
usage() {
    cat <<'EOF'
Usage: scripts/cyberdef/send-ticket.sh [options]

Creates one ticket and mails the people allocated to the AWS account.

Ticket fields (flags override values from --file):
  --file FILE            JSON object with any ticket fields (see examples/ticket.json)
  --account ID           12-digit AWS account id                       (required)
  --title TEXT           short summary, max 200 characters              (required)
  --severity LEVEL       CRITICAL | HIGH | MEDIUM | LOW | INFO          (required)
  --description TEXT     markdown description                           (required,
  --description-file F   ... or read it from a file                      one of both)
  --action TEXT          recommended action for the owners
  --ref ID               your own id; resending it returns the existing ticket
  --cve ID               CVE id, repeatable
  --resource SPEC        affected resource, repeatable: an ARN (type and region are derived),
                         or TYPE=ID[@REGION] such as HOSTNAME=web01.example.com
  --link URL             https reference link, repeatable
  --source NAME          originating system, e.g. guardduty
  --due ISO-8601         due date; may only shorten the severity SLA

Delivery:
  --to ADDR[,ADDR]       mail these addresses instead of the account owners (repeatable,
                         max 20; each must be known to SecMan or on an allowed domain)
  --allow-unrouted       create the ticket even if nobody is allocated to the account
  --dry-run              let SecMan validate and resolve recipients; store and send nothing
  --print-request        print the MCP request and exit; no network, no credentials needed
  --as EMAIL             delegated user (default: SECMAN_CYBERDEF_EMAIL, then SECMAN_ADMIN_EMAIL)
  -h, --help

Output: the ticket result as JSON (outcome CREATED, DUPLICATE, WOULD_CREATE, or REJECTED with
field-keyed errors).
Exit codes: 0 ok, 1 error, 2 usage, 3 rejected.
EOF
}

FILE="" ACCOUNT="" TITLE="" SEVERITY="" DESCRIPTION="" ACTION="" REF="" SOURCE="" DUE=""
CVES=() RESOURCES=() LINKS=() RECIPIENTS=() DRY_RUN=false ALLOW_UNROUTED=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --file) FILE="${2:?--file needs a value}"; shift 2 ;;
        --account) ACCOUNT="${2:?--account needs a value}"; shift 2 ;;
        --title) TITLE="${2:?--title needs a value}"; shift 2 ;;
        --severity) SEVERITY="${2:?--severity needs a value}"; shift 2 ;;
        --description) DESCRIPTION="${2:?--description needs a value}"; shift 2 ;;
        --description-file) DESCRIPTION="$(cat -- "${2:?--description-file needs a value}")"; shift 2 ;;
        --action) ACTION="${2:?--action needs a value}"; shift 2 ;;
        --ref) REF="${2:?--ref needs a value}"; shift 2 ;;
        --cve) CVES+=("${2:?--cve needs a value}"); shift 2 ;;
        --resource) RESOURCES+=("${2:?--resource needs a value}"); shift 2 ;;
        --link) LINKS+=("${2:?--link needs a value}"); shift 2 ;;
        --source) SOURCE="${2:?--source needs a value}"; shift 2 ;;
        --due) DUE="${2:?--due needs a value}"; shift 2 ;;
        --to) RECIPIENTS+=("${2:?--to needs a value}"); shift 2 ;;
        --allow-unrouted) ALLOW_UNROUTED=true; shift ;;
        --dry-run) DRY_RUN=true; shift ;;
        --print-request) PRINT_REQUEST=true; shift ;;
        --as) DELEGATE_EMAIL="${2:?--as needs a value}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

require_tools
ensure_credentials "$SCRIPT_PATH" "${ORIGINAL_ARGS[@]}"

BASE='{}'
if [[ -n "$FILE" ]]; then
    BASE="$(jq -ce 'if type == "object" then . else error("not a JSON object") end' -- "$FILE")" \
        || usage_error "--file must contain one JSON object"
fi

# Resource flags: TYPE=IDENTIFIER[@REGION]
RESOURCE_JSON='[]'
for spec in "${RESOURCES[@]+"${RESOURCES[@]}"}"; do
    if [[ "$spec" == arn:* ]]; then
        RESOURCE_JSON="$(jq -c --arg i "$spec" '. + [{identifier:$i}]' <<<"$RESOURCE_JSON")"
        continue
    fi
    [[ "$spec" == *=* ]] || usage_error "--resource must be an ARN or TYPE=IDENTIFIER[@REGION]: $spec"
    type="${spec%%=*}" rest="${spec#*=}" region=""
    if [[ "$rest" == *@* && "${rest##*@}" =~ ^[a-z0-9-]+$ ]]; then region="${rest##*@}"; rest="${rest%@*}"; fi
    RESOURCE_JSON="$(jq -c --arg t "$type" --arg i "$rest" --arg r "$region" \
        '. + [{type:$t, identifier:$i} + (if $r == "" then {} else {region:$r} end)]' <<<"$RESOURCE_JSON")"
done

RECIPIENT_JSON="$(json_list "${RECIPIENTS[*]+"${RECIPIENTS[*]}"}")"
[[ "$RECIPIENT_JSON" == "[]" ]] || check_recipients "$RECIPIENT_JSON"

TICKET="$(jq -c \
    --arg account "$ACCOUNT" --arg title "$TITLE" --arg severity "$SEVERITY" --arg description "$DESCRIPTION" \
    --arg action "$ACTION" --arg ref "$REF" --arg source "$SOURCE" --arg due "$DUE" \
    --argjson cves "$(printf '%s\n' "${CVES[@]+"${CVES[@]}"}" | jq -R . | jq -sc 'map(select(length > 0))')" \
    --argjson links "$(printf '%s\n' "${LINKS[@]+"${LINKS[@]}"}" | jq -R . | jq -sc 'map(select(length > 0))')" \
    --argjson resources "$RESOURCE_JSON" '
    def set($k; $v): if ($v | length) > 0 then .[$k] = $v else . end;
    set("awsAccountId"; $account) | set("title"; $title) | set("severity"; $severity)
    | set("description"; $description) | set("recommendedAction"; $action) | set("externalReference"; $ref)
    | set("sourceSystem"; $source) | set("dueAt"; $due) | set("cves"; $cves) | set("references"; $links)
    | set("resources"; $resources)
    ' <<<"$BASE")"

for field in awsAccountId title severity description; do
    jq -e --arg f "$field" '.[$f] | type == "string" and length > 0' <<<"$TICKET" >/dev/null \
        || usage_error "Missing required field: $field (flag or --file)"
done
jq -e '.awsAccountId | test("^[0-9]{12}$")' <<<"$TICKET" >/dev/null || usage_error "AWS account id must be 12 digits"

DELIVERY="$(jq -nc --argjson dry "$DRY_RUN" --argjson unrouted "$ALLOW_UNROUTED" --argjson to "$RECIPIENT_JSON" '
    {} + (if $dry then {dryRun: true} else {} end) + (if $unrouted then {allowUnrouted: true} else {} end)
    + (if ($to | length) > 0 then {recipients: $to} else {} end)')"
ARGUMENTS="$(jq -c --argjson delivery "$DELIVERY" '{ticket: ., delivery: $delivery}' <<<"$TICKET")"
[[ "$PRINT_REQUEST" != true ]] || { print_request create_cyberdef_ticket "$ARGUMENTS"; exit 0; }
RESULT="$(mcp_call create_cyberdef_ticket "$ARGUMENTS")" || exit 1
printf '%s\n' "$RESULT" | jq .
[[ "$(jq -r '.outcome' <<<"$RESULT")" != "REJECTED" ]] || exit 3
