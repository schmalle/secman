#!/usr/bin/env bash
# Atomic reservation shared by all SecMan worktrees on this host.

secman_e2e_acquire_lock() {
    local lock_dir="$1"
    if ! mkdir -m 700 "$lock_dir" 2>/dev/null; then
        echo "Full-stack E2E is reserved: $lock_dir. Retry after the owning run finishes." >&2
        echo "If interrupted, verify no isolated test processes remain before removing the reservation." >&2
        return 2
    fi
    SECMAN_E2E_LOCK_DIR="$lock_dir"
    printf '%s\n' "$$" > "$lock_dir/owner-pid"
}

secman_e2e_release_lock() {
    [[ -n "${SECMAN_E2E_LOCK_DIR:-}" ]] || return 0
    rm -f "$SECMAN_E2E_LOCK_DIR/owner-pid"
    rmdir "$SECMAN_E2E_LOCK_DIR" || {
        echo "Could not release E2E reservation: $SECMAN_E2E_LOCK_DIR" >&2
        return 1
    }
    unset SECMAN_E2E_LOCK_DIR
}
