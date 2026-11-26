#!/usr/bin/env bash
# Cyber Defence side: list, inspect, close, reopen and comment on any ticket.
set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")
# shellcheck source=lib.sh
source "$(dirname "$SCRIPT_PATH")/lib.sh"
# shellcheck source=ticket-common.sh
source "$(dirname "$SCRIPT_PATH")/ticket-common.sh"

ticket_main defender "$(basename "$SCRIPT_PATH")" "$@"
