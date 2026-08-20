#!/usr/bin/env bash
# PID 1 of the Secman shielded dev container.
# =============================================================================
# Order matters and is not negotiable:
#
#   1. align the in-container `dev` user with the owner of the bind-mounted
#      /workspace, so files written from inside stay usable on the Mac;
#   2. render the egress allowlist;
#   3. raise the firewall;
#   4. start squid behind it;
#   5. prove the gate works (one allowed host, one denied host);
#   6. optionally start MariaDB and the TLS front door;
#   7. hand over to whatever the operator asked for, or idle so that
#      `container exec` sessions can attach.
#
# Steps 3-5 run before any agent or build process can exist. There is no window
# in which the container has unrestricted egress.
# =============================================================================
set -euo pipefail

RUN_DIR=/run/secman-dev
EGRESS_DIR="$RUN_DIR/egress"
ALLOWLIST_RENDERED="$EGRESS_DIR/allowlist.rendered"

: "${SECMAN_DEV_USER:=dev}"
: "${SECMAN_DEV_WORKSPACE:=/workspace}"
: "${SECMAN_DEV_WITH_DB:=0}"
: "${SECMAN_DEV_WITH_TLS:=1}"
: "${SECMAN_DEV_TLS_HOST:=localhost}"
: "${SECMAN_DEV_DB_NAME:=secman}"
: "${SECMAN_DEV_DB_USER:=secman}"
: "${SECMAN_EGRESS_EXTRA_DOMAINS:=}"

# Exported, not just set: render-egress-allowlist, init-egress-firewall and
# devctl all run as separate processes and read these from the environment.
export SECMAN_DEV_TLS_HOST SECMAN_DEV_DB_NAME SECMAN_DEV_DB_USER SECMAN_EGRESS_EXTRA_DOMAINS

log()  { printf '[secman-dev] %s\n' "$*"; }
warn() { printf '[secman-dev] WARNING: %s\n' "$*" >&2; }
die()  { printf '[secman-dev] FATAL: %s\n' "$*" >&2; exit 1; }

mkdir -p "$EGRESS_DIR"

# -----------------------------------------------------------------------------
# 1. Workspace + user alignment
# -----------------------------------------------------------------------------
[ -d "$SECMAN_DEV_WORKSPACE" ] || die "$SECMAN_DEV_WORKSPACE is not mounted — start the container via scripts/container/secman-container.sh"

# virtiofs presents the Mac's ownership verbatim (a normal macOS account is uid
# 501, not 1000). Creating `dev` with a fixed uid would make every file the
# container writes land as the wrong owner on the host, so derive it instead.
WS_UID=$(stat -c '%u' "$SECMAN_DEV_WORKSPACE")
WS_GID=$(stat -c '%g' "$SECMAN_DEV_WORKSPACE")
if [ "$WS_UID" -eq 0 ]; then
    warn "$SECMAN_DEV_WORKSPACE is owned by root; falling back to uid/gid 1000 for '$SECMAN_DEV_USER'"
    WS_UID=1000; WS_GID=1000
fi

if ! getent group "$WS_GID" >/dev/null 2>&1; then
    # The preferred name may already be taken by a distro group at another gid;
    # the gid is what has to match the host, the name is cosmetic.
    groupadd -g "$WS_GID" "$SECMAN_DEV_USER" 2>/dev/null \
        || groupadd -g "$WS_GID" "grp$WS_GID"
fi

if ! id -u "$SECMAN_DEV_USER" >/dev/null 2>&1; then
    # -M, not -m: /home/dev is a named volume that is already mounted, and
    # `useradd -m` onto an existing directory skips the skeleton silently, which
    # would leave the account without a .bashrc or .profile. Populate it below
    # instead, and only when it is genuinely empty.
    useradd -u "$WS_UID" -g "$WS_GID" -M -d "/home/$SECMAN_DEV_USER" -s /bin/bash "$SECMAN_DEV_USER"
    # Deliberately no sudo: the firewall and the proxy are root-owned, and an
    # agent that can sudo can take them down. `container exec -u root` from the
    # Mac remains available to the human when something genuinely needs root.
    log "created user $SECMAN_DEV_USER (uid=$WS_UID gid=$WS_GID) to match $SECMAN_DEV_WORKSPACE"
fi
DEV_HOME=$(getent passwd "$SECMAN_DEV_USER" | cut -d: -f6)
mkdir -p "$DEV_HOME"
if [ -z "$(ls -A "$DEV_HOME" 2>/dev/null)" ]; then
    cp -a /etc/skel/. "$DEV_HOME/"
    chown -R "$WS_UID:$WS_GID" "$DEV_HOME"
    log "populated $DEV_HOME from /etc/skel (first start on this volume)"
fi

# Persisted caches and agent state arrive as named volumes owned by root.
for d in "$DEV_HOME/.gradle" "$DEV_HOME/.npm" "$DEV_HOME/.cache" \
         "$DEV_HOME/.claude" "$DEV_HOME/.kimi" "$DEV_HOME/.config" \
         "$DEV_HOME/.local/share/proton-pass-cli"; do
    mkdir -p "$d"
    chown "$WS_UID:$WS_GID" "$d"
done
chown "$WS_UID:$WS_GID" "$DEV_HOME" 2>/dev/null || true

# -----------------------------------------------------------------------------
# 2. Render the egress allowlist
# -----------------------------------------------------------------------------
# Rendering is a separate script because `devctl egress refresh` has to produce
# byte-identical output later, and because both enforcement layers — squid's ACL
# and the firewall's address-mode fallback — must read exactly one list.
ALLOWED_COUNT=$(/usr/local/sbin/render-egress-allowlist "$ALLOWLIST_RENDERED") \
    || die "could not render the egress allowlist — refusing to start with an unusable policy"
log "egress allowlist: $ALLOWED_COUNT domains"

# -----------------------------------------------------------------------------
# 3. Firewall
# -----------------------------------------------------------------------------
ALLOWLIST_RENDERED="$ALLOWLIST_RENDERED" /usr/local/sbin/init-egress-firewall

# -----------------------------------------------------------------------------
# 4. Squid
# -----------------------------------------------------------------------------
install -d -o proxy -g proxy /var/log/squid /var/spool/squid /var/cache/squid
squid -N -f /etc/secman-dev/egress/squid.conf -z >/dev/null 2>&1 || true
squid -f /etc/secman-dev/egress/squid.conf
for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/3128) 2>/dev/null; then exec 3>&- 2>/dev/null || true; break; fi
    sleep 0.5
done
(exec 3<>/dev/tcp/127.0.0.1/3128) 2>/dev/null || die "squid did not come up on 127.0.0.1:3128 — see /var/log/squid/cache.log"
exec 3>&- 2>/dev/null || true
log "egress proxy listening on 127.0.0.1:3128"

# -----------------------------------------------------------------------------
# 5. Prove the gate
# -----------------------------------------------------------------------------
# A firewall nobody tested is a firewall nobody has. Two probes: one host that
# must work and one that must not. The negative probe is the load-bearing one —
# if a non-allowlisted host is reachable the containment is not there, and
# starting anyway would hand the agents an environment whose shielding is a
# guess. So that one is fatal; the positive probe only warns, because a
# temporarily unreachable GitHub is a network problem, not a policy failure.
if [ "${SECMAN_DEV_SKIP_EGRESS_SELFTEST:-0}" != "1" ]; then
    probe() { curl -sS --max-time 20 -o /dev/null -w '%{http_code}' -x http://127.0.0.1:3128 "$1" 2>/dev/null || echo 000; }
    allowed_code=$(probe https://api.github.com/meta)
    denied_code=$(probe https://www.google.com/)
    case "$allowed_code" in
        2*|3*|4*) log "egress self-test: allowlisted host reachable (HTTP $allowed_code)" ;;
        *)        warn "egress self-test: allowlisted host unreachable (HTTP $allowed_code) — check DNS and the allowlist" ;;
    esac
    if [ "$denied_code" = "403" ]; then
        log "egress self-test: non-allowlisted host refused by the proxy (HTTP 403)"
    else
        die "egress self-test FAILED: a non-allowlisted host returned HTTP $denied_code instead of 403. Refusing to start."
    fi
fi

# -----------------------------------------------------------------------------
# 6. Optional services
# -----------------------------------------------------------------------------
if [ "$SECMAN_DEV_WITH_DB" = "1" ]; then
    devctl db start || warn "MariaDB failed to start — run 'devctl db start' for the log"
fi
if [ "$SECMAN_DEV_WITH_TLS" = "1" ]; then
    devctl tls start || warn "nginx failed to start — run 'devctl tls start' for the log"
fi

# -----------------------------------------------------------------------------
# 7. Hand over
# -----------------------------------------------------------------------------
shutdown() {
    log "shutting down"
    devctl tls stop  >/dev/null 2>&1 || true
    devctl db stop   >/dev/null 2>&1 || true
    squid -k shutdown -f /etc/secman-dev/egress/squid.conf >/dev/null 2>&1 || true
    exit 0
}
trap shutdown TERM INT

log "ready — attach with: ./scripts/container/secman-container.sh shell"

if [ "$#" -gt 0 ]; then
    setpriv --reuid "$WS_UID" --regid "$WS_GID" --init-groups \
        --inh-caps=-all -- /bin/bash -lc 'cd "$0" && exec "$@"' "$SECMAN_DEV_WORKSPACE" "$@" &
    wait $!
    shutdown
fi

# Idle. The container is a long-lived shielded environment that many
# `container exec` sessions attach to; there is no single foreground process
# whose exit should tear it down.
while :; do sleep 3600 & wait $!; done
