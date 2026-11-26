#!/usr/bin/env bash
# Example handler for ticket-updates.sh --on-event. Receives one event as JSON on stdin.
# Exit 0 when the event is handled; any other exit stops the run and the event is retried.
#
# What it does: prints a line per owner action, and appends every event to a JSONL log that
# a SOAR or ticketing system can pick up. Adapt the `case` to forward events elsewhere.
set -euo pipefail

LOG="${CYBERDEF_EVENT_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/secman-cyberdef/events.jsonl}"
mkdir -p "$(dirname "$LOG")"

event="$(cat)"
printf '%s\n' "$event" >>"$LOG"

kind="$(jq -r '"\(.eventType):\(.actorKind):\(.toStatus // "")"' <<<"$event")"
key="$(jq -r '.ticketKey // ("#" + (.ticketId | tostring))' <<<"$event")"
who="$(jq -r '.actorUsername // "unknown"' <<<"$event")"
note="$(jq -r '.comment // "" | gsub("[\\r\\n]+"; " ") | .[0:200]' <<<"$event")"

case "$kind" in
    STATUS_CHANGED:OWNER:CLOSED)
        echo "[$key] closed by owner $who: $note"
        echo "        verify, then keep it closed or: ticket-status.sh reopen --id $(jq -r .ticketId <<<"$event") --comment '...'" ;;
    STATUS_CHANGED:OWNER:OPEN)
        echo "[$key] reopened by owner $who: $note" ;;
    COMMENT:OWNER:*)
        echo "[$key] question/comment from $who: $note"
        echo "        answer with: ticket-status.sh comment --id $(jq -r .ticketId <<<"$event") --comment '...'" ;;
    *) ;;  # our own creations and changes: logged only
esac
