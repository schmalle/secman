#!/usr/bin/env bash
# Shared implementation of ticket-status.sh (Cyber Defence side) and my-tickets.sh (account
# owner side). Source it after lib.sh and call `ticket_main <side> "$@"`; do not run it.

ticket_usage() {
    local script="$1" side="$2"
    cat <<EOF
Usage: scripts/cyberdef/$script <command> [options]

Commands:
  list                    list tickets (--status OPEN|CLOSED, --account ID, --overdue, --page N, --size N)
  get                     show one ticket with its activity (--id N or --key CD-YYYY-NNNNNN$(
                              [[ "$side" == defender ]] && printf ' or --ref YOUR_REF'))
  close                   close a ticket (--id N, --comment TEXT$( [[ "$side" == owner ]] && printf ' required'))
  reopen                  reopen a closed ticket (--id N, --comment TEXT required)
  comment                 add a comment without changing the status (--id N, --comment TEXT)

Options for close/reopen:
  --expected-version N    fail with VERSION_CONFLICT if the ticket changed since you read it
  --dry-run               check the change and show who would be mailed; change nothing

Common:
  --print-request         print the MCP request and exit; no network, no credentials needed
  --as EMAIL              delegated user (default: SECMAN_CYBERDEF_EMAIL, then SECMAN_ADMIN_EMAIL)
  -h, --help

Exit codes: 0 ok, 1 error (the message starts with the error code), 2 usage.
EOF
}

# Tool names per side. The owner tools are scoped to the delegated user's own accounts.
ticket_tool() {
    local side="$1" action="$2"
    case "$side:$action" in
        defender:list) echo list_cyberdef_tickets ;;
        defender:get) echo get_cyberdef_ticket ;;
        defender:status) echo set_cyberdef_ticket_status ;;
        owner:list) echo list_my_security_tickets ;;
        owner:get) echo get_my_security_ticket ;;
        owner:status) echo update_my_security_ticket_status ;;
        *:comment) echo add_cyberdef_ticket_comment ;;
    esac
}

# Parses the command line into the globals T_* used by ticket_request.
ticket_parse() {
    local side="$1" script="$2"; shift 2
    T_COMMAND="${1:-}"
    [[ -n "$T_COMMAND" && "$T_COMMAND" != -* ]] || { ticket_usage "$script" "$side" >&2; exit 2; }
    shift
    T_ID="" T_KEY="" T_REF="" T_COMMENT="" T_VERSION="" T_STATUS="" T_ACCOUNT="" T_OVERDUE=false T_PAGE=0 T_SIZE=50 T_DRY=false
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --id) T_ID="${2:?--id needs a value}"; shift 2 ;;
            --key) T_KEY="${2:?--key needs a value}"; shift 2 ;;
            --ref) [[ "$side" == defender ]] || usage_error "--ref is a Cyber Defence lookup"; T_REF="${2:?--ref needs a value}"; shift 2 ;;
            --comment) T_COMMENT="${2:?--comment needs a value}"; shift 2 ;;
            --expected-version) T_VERSION="${2:?--expected-version needs a value}"; shift 2 ;;
            --status) T_STATUS="${2:?--status needs a value}"; shift 2 ;;
            --account) T_ACCOUNT="${2:?--account needs a value}"; shift 2 ;;
            --overdue) T_OVERDUE=true; shift ;;
            --page) T_PAGE="${2:?--page needs a value}"; shift 2 ;;
            --size) T_SIZE="${2:?--size needs a value}"; shift 2 ;;
            --dry-run) T_DRY=true; shift ;;
            --print-request) PRINT_REQUEST=true; shift ;;
            --as) DELEGATE_EMAIL="${2:?--as needs a value}"; shift 2 ;;
            -h|--help) ticket_usage "$script" "$side"; exit 0 ;;
            *) echo "Unknown option: $1" >&2; exit 2 ;;
        esac
    done
    [[ -z "$T_ID" || "$T_ID" =~ ^[0-9]+$ ]] || usage_error "--id must be a number"
    [[ -z "$T_VERSION" || "$T_VERSION" =~ ^[0-9]+$ ]] || usage_error "--expected-version must be a number"
    [[ "$T_PAGE" =~ ^[0-9]+$ && "$T_SIZE" =~ ^[0-9]+$ ]] || usage_error "--page and --size must be numbers"
}

# Prints "<tool> <arguments-json>" for the parsed command, or fails with a usage error.
ticket_request() {
    local side="$1"
    case "$T_COMMAND" in
        list)
            printf '%s ' "$(ticket_tool "$side" list)"
            jq -nc --arg s "$T_STATUS" --arg a "$T_ACCOUNT" --argjson o "$T_OVERDUE" --argjson p "$T_PAGE" --argjson z "$T_SIZE" '
                {page:$p, size:$z} + (if $s != "" then {status:[$s | ascii_upcase]} else {} end)
                + (if $a != "" then {awsAccountId:$a} else {} end) + (if $o then {overdue:true} else {} end)' ;;
        get)
            [[ -n "$T_ID$T_KEY$T_REF" ]] || usage_error "get needs --id, --key or --ref"
            printf '%s ' "$(ticket_tool "$side" get)"
            jq -nc --arg i "$T_ID" --arg k "$T_KEY" --arg r "$T_REF" '
                (if $i != "" then {id:($i | tonumber)} else {} end) + (if $k != "" then {ticketKey:$k} else {} end)
                + (if $r != "" then {externalReference:$r} else {} end)' ;;
        close|reopen)
            [[ -n "$T_ID" ]] || usage_error "$T_COMMAND needs --id"
            [[ "$T_COMMAND" == close && "$side" == defender || -n "$T_COMMENT" ]] || usage_error "$T_COMMAND needs --comment"
            printf '%s ' "$(ticket_tool "$side" status)"
            jq -nc --argjson i "$T_ID" --arg to "$([[ "$T_COMMAND" == close ]] && echo CLOSED || echo OPEN)" \
                --arg c "$T_COMMENT" --arg v "$T_VERSION" --argjson d "$T_DRY" '
                {id:$i, toStatus:$to} + (if $c != "" then {comment:$c} else {} end)
                + (if $v != "" then {expectedVersion:($v | tonumber)} else {} end) + (if $d then {dryRun:true} else {} end)' ;;
        comment)
            [[ -n "$T_ID" && -n "$T_COMMENT" ]] || usage_error "comment needs --id and --comment"
            printf '%s ' "$(ticket_tool "$side" comment)"
            jq -nc --argjson i "$T_ID" --arg c "$T_COMMENT" '{id:$i, comment:$c}' ;;
        *) usage_error "Unknown command: $T_COMMAND" ;;
    esac
}

# Entry point of both wrappers: parse, build the request, then print it or send it.
ticket_main() {
    local side="$1" script="$2"; shift 2
    ticket_parse "$side" "$script" "$@"
    local request tool arguments
    request="$(ticket_request "$side")" || exit $?
    tool="${request%% *}" arguments="${request#* }"
    require_tools
    ensure_credentials "$SCRIPT_PATH" "${ORIGINAL_ARGS[@]}"
    [[ "$PRINT_REQUEST" != true ]] || { print_request "$tool" "$arguments"; exit 0; }
    mcp_call "$tool" "$arguments" | jq .
}
