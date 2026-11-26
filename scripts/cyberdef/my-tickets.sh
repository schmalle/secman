#!/usr/bin/env bash
# Account-owner side: your tickets only. Run it --as the owner (the API key needs TICKETS_RESPOND).
set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")
# shellcheck source=lib.sh
source "$(dirname "$SCRIPT_PATH")/lib.sh"
# shellcheck source=ticket-common.sh
source "$(dirname "$SCRIPT_PATH")/ticket-common.sh"

ticket_main owner "$(basename "$SCRIPT_PATH")" "$@"
