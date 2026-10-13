#!/usr/bin/env bash
# Verify cross-worktree exclusion and cleanup without services, secrets, or MariaDB.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
LOCK_LIB="$REPO_ROOT/scripts/test/lib/full-stack-lock.sh"
source "$LOCK_LIB"
LOCK_DIR="$TEST_DIR/reservation"
secman_e2e_acquire_lock "$LOCK_DIR"
[[ "$(cat "$LOCK_DIR/owner-pid")" == "$$" ]]

# A different process/worktree must fail without releasing the owner's lock.
set +e
bash -c 'source "$1"; secman_e2e_acquire_lock "$2"' -- "$LOCK_LIB" "$LOCK_DIR"
result=$?
set -e
[[ "$result" == 2 && -d "$LOCK_DIR" ]]
secman_e2e_release_lock
[[ ! -e "$LOCK_DIR" ]]
secman_e2e_release_lock

# Early failure releases the reservation and preserves the driver's exit code.
set +e
bash -c 'set -e; source "$1"; secman_e2e_acquire_lock "$2"; trap secman_e2e_release_lock EXIT; exit 7' \
    -- "$LOCK_LIB" "$LOCK_DIR"
result=$?
set -e
[[ "$result" == 7 && ! -e "$LOCK_DIR" ]]

# Stale/unverified reservations are preserved instead of being reclaimed by PID.
mkdir "$LOCK_DIR"
printf '%s\n' 99999999 > "$LOCK_DIR/owner-pid"
set +e
bash -c 'source "$1"; secman_e2e_acquire_lock "$2"' -- "$LOCK_LIB" "$LOCK_DIR"
result=$?
set -e
[[ "$result" == 2 && "$(cat "$LOCK_DIR/owner-pid")" == 99999999 ]]
rm "$LOCK_DIR/owner-pid"
rmdir "$LOCK_DIR"

# A subsequent run can reserve and release the same slot.
secman_e2e_acquire_lock "$LOCK_DIR"
secman_e2e_release_lock
echo "Full-stack reservation: contention, failure cleanup, stale preservation, and reuse passed"
