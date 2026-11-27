#!/usr/bin/env bash
# Read what happened to CYBERDEF tickets since last time: creations, owners closing or
# reopening tickets, and comments. The cursor is stored after each handled event, so every
# event is processed once even across restarts.
set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")
# shellcheck source=lib.sh
source "$(dirname "$SCRIPT_PATH")/lib.sh"

# --help text; also printed on a usage error.
usage() {
    cat <<'EOF'
Usage: scripts/cyberdef/ticket-updates.sh [options]

  --state FILE       cursor file (default: ${XDG_STATE_HOME:-~/.local/state}/secman-cyberdef/cursor)
  --since ID         start after this event id instead of the stored cursor (0 = everything)
  --limit N          events per request, 1-500 (default 100)
  --on-event CMD     run CMD once per event with the event JSON on stdin, e.g.
                     examples/on-event.sh. A non-zero exit stops the run and leaves the
                     cursor before that event, so it is retried next time.
  --follow           keep polling instead of exiting when caught up
  --interval SEC     seconds between polls with --follow (default 60)
  --print-request    print the first MCP request and exit; no network, no credentials
  --as EMAIL         delegated user (default: SECMAN_CYBERDEF_EMAIL, then SECMAN_ADMIN_EMAIL)
  -h, --help

Without --on-event every event is printed as one JSON line on stdout. Fields: id, ticketId,
ticketKey, eventType (CREATED | STATUS_CHANGED | COMMENT), actorUsername, actorKind
(OWNER | DEFENDER), fromStatus, toStatus (OPEN | CLOSED), comment, createdAt.
EOF
}

STATE="${XDG_STATE_HOME:-$HOME/.local/state}/secman-cyberdef/cursor"
SINCE="" LIMIT=100 HANDLER="" FOLLOW=false INTERVAL=60
while [[ $# -gt 0 ]]; do
    case "$1" in
        --state) STATE="${2:?--state needs a value}"; shift 2 ;;
        --since) SINCE="${2:?--since needs a value}"; shift 2 ;;
        --limit) LIMIT="${2:?--limit needs a value}"; shift 2 ;;
        --on-event) HANDLER="${2:?--on-event needs a value}"; shift 2 ;;
        --follow) FOLLOW=true; shift ;;
        --interval) INTERVAL="${2:?--interval needs a value}"; shift 2 ;;
        --print-request) PRINT_REQUEST=true; shift ;;
        --as) DELEGATE_EMAIL="${2:?--as needs a value}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done
[[ "$LIMIT" =~ ^[0-9]+$ ]] && (( LIMIT >= 1 && LIMIT <= 500 )) || usage_error "--limit must be 1-500"
[[ "$INTERVAL" =~ ^[0-9]+$ ]] && (( INTERVAL >= 5 )) || usage_error "--interval must be at least 5 seconds"
[[ -z "$SINCE" || "$SINCE" =~ ^[0-9]+$ ]] || usage_error "--since must be an event id"
[[ -z "$HANDLER" || -x "$HANDLER" ]] || usage_error "--on-event must name an executable file: $HANDLER"

require_tools
ensure_credentials "$SCRIPT_PATH" "${ORIGINAL_ARGS[@]}"

# --since wins over the stored cursor; a missing file means "from the beginning".
read_cursor() {
    if [[ -n "$SINCE" ]]; then printf '%s' "$SINCE"; return; fi
    if [[ -f "$STATE" ]]; then
        local stored
        stored="$(tr -d '[:space:]' <"$STATE")"
        [[ "$stored" =~ ^[0-9]+$ ]] || die "Cursor file $STATE is corrupt; fix it or pass --since"
        printf '%s' "$stored"
    else
        printf '0'
    fi
}

# Write-then-rename, so a crash never leaves a half-written cursor.
save_cursor() {
    mkdir -p "$(dirname "$STATE")"
    local tmp="$STATE.tmp.$$"
    printf '%s\n' "$1" >"$tmp"
    mv -f "$tmp" "$STATE"
    SINCE=""
}

# Hands one event to --on-event, or prints it as a JSON line when there is no handler.
deliver_event() {
    if [[ -z "$HANDLER" ]]; then
        printf '%s\n' "$1"
    else
        printf '%s' "$1" | "$HANDLER"
    fi
}

cursor="$(read_cursor)"
if [[ "$PRINT_REQUEST" == true ]]; then
    print_request get_cyberdef_ticket_updates "$(jq -nc --argjson s "$cursor" --argjson l "$LIMIT" '{sinceEventId:$s, limit:$l}')"
    exit 0
fi

while true; do
    page="$(mcp_call get_cyberdef_ticket_updates "$(jq -nc --argjson s "$cursor" --argjson l "$LIMIT" '{sinceEventId:$s, limit:$l}')")" \
        || exit 1
    while IFS= read -r event; do
        id="$(jq -r '.id' <<<"$event")"
        if ! deliver_event "$event"; then
            echo "Handler failed on event $id; cursor stays at $cursor" >&2
            exit 1
        fi
        cursor="$id"
        save_cursor "$cursor"
    done < <(jq -c '.events[]' <<<"$page")
    if [[ "$(jq -r '.hasMore' <<<"$page")" == true ]]; then
        continue
    fi
    [[ "$FOLLOW" == true ]] || break
    sleep "$INTERVAL"
done
