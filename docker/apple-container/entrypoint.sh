#!/usr/bin/env bash
# PID 1 of the Secman shielded dev container.
# =============================================================================
# Order matters and is not negotiable:
#
#   1. align the in-container `dev` user with the owner of the bind-mounted
#      /workspace, so files written from inside stay usable on the Mac;
#   2. settle which database the stack will use — the container's own, the one
#      installed on the Mac, or neither — because 'host' needs a hole in the
#      firewall and therefore has to be decided before the firewall is built;
#   3. render the egress allowlist;
#   4. raise the firewall — the only egress control there is, no proxy;
#   5. prove it works (one allowed host, one denied host);
#   6. optionally start MariaDB and the TLS front door;
#   7. keep the allow-set fresh, because the addresses behind those names move;
#   8. hand over to whatever the operator asked for, or idle so that
#      `container exec` sessions can attach.
#
# Steps 4-5 run before any agent or build process can exist. There is no window
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
: "${SECMAN_DEV_DB_MODE:=}"
: "${SECMAN_DEV_DB_HOST:=}"
: "${SECMAN_DEV_DB_PORT:=3306}"
: "${SECMAN_DEV_DB_NAME:=secman}"
: "${SECMAN_DEV_DB_USER:=secman}"
: "${SECMAN_DEV_DB_PARAMS:=}"
: "${SECMAN_EGRESS_EXTRA_DOMAINS:=}"

DB_DIR="$RUN_DIR/db"
DB_ENV="$DB_DIR/env"

# Exported, not just set: render-egress-allowlist, init-egress-firewall and
# devctl all run as separate processes and read these from the environment.
export SECMAN_DEV_TLS_HOST SECMAN_DEV_DB_NAME SECMAN_DEV_DB_USER SECMAN_EGRESS_EXTRA_DOMAINS
export SECMAN_DEV_DB_MODE SECMAN_DEV_DB_HOST SECMAN_DEV_DB_PORT SECMAN_DEV_DB_PARAMS

log()  { printf '[secman-dev] %s\n' "$*"; }
warn() { printf '[secman-dev] WARNING: %s\n' "$*" >&2; }
die()  { printf '[secman-dev] FATAL: %s\n' "$*" >&2; exit 1; }

mkdir -p "$EGRESS_DIR" "$DB_DIR"

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
    # Deliberately no sudo: the firewall and its allowlist are root-owned, and
    # an agent that can sudo can take them down. `container exec -u root` from
    # the Mac remains available when something genuinely needs root.
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
# 2. Settle the database selection
# -----------------------------------------------------------------------------
# Three modes, chosen on the Mac with `secman-container.sh up --db ...`:
#
#   container  the MariaDB this container runs itself. Reached over loopback,
#              so the firewall needs nothing.
#   host       the MariaDB installed on the Mac. That is a TCP connection out of
#              the VM on port 3306 — a port the egress policy does not carry and
#              an address the allowlist does not contain — so it needs one
#              explicit rule, which is why this step runs before the firewall.
#   none       neither. DB_CONNECT stays whatever pass-cli resolves.
#
# The result is written to $DB_ENV, which is the single source both the firewall
# (including a later `refresh-egress`, which does not inherit this environment)
# and every login shell read. Deciding it twice is how the firewall and the JDBC
# URL end up disagreeing.

# An older host script knows only the boolean. Derive the mode from it so a new
# image still works when driven by one.
if [ -z "$SECMAN_DEV_DB_MODE" ]; then
    if [ "$SECMAN_DEV_WITH_DB" = "1" ]; then SECMAN_DEV_DB_MODE=container; else SECMAN_DEV_DB_MODE=none; fi
fi

case "$SECMAN_DEV_DB_MODE" in
    container|host|none) ;;
    *) die "SECMAN_DEV_DB_MODE must be container, host or none (got '$SECMAN_DEV_DB_MODE')" ;;
esac

# Interpolated into a JDBC URL and, for 'host', into an iptables rule. Neither
# can be parameter-bound, so a closed character allowlist is the substitute
# (CLAUDE.md §A03).
printf '%s\n' "$SECMAN_DEV_DB_PORT" | grep -qE '^[0-9]{1,5}$' \
    && [ "$SECMAN_DEV_DB_PORT" -ge 1 ] && [ "$SECMAN_DEV_DB_PORT" -le 65535 ] \
    || die "SECMAN_DEV_DB_PORT must be a TCP port between 1 and 65535 (got '$SECMAN_DEV_DB_PORT')"
printf '%s\n' "$SECMAN_DEV_DB_NAME" | grep -qE '^[A-Za-z0-9_]{1,64}$' \
    || die "SECMAN_DEV_DB_NAME must match ^[A-Za-z0-9_]{1,64}\$ (got '$SECMAN_DEV_DB_NAME')"
printf '%s\n' "$SECMAN_DEV_DB_PARAMS" | grep -qE '^(\?[A-Za-z0-9_=&.%-]{1,200})?$' \
    || die "SECMAN_DEV_DB_PARAMS must be a '?key=value&...' query string (got '$SECMAN_DEV_DB_PARAMS')"

# RFC 1918, loopback, link-local and the CGNAT range that Apple's vmnet may hand
# out. Anything outside these is not "the database on your Mac".
is_local_v4() {
    case "$1" in
        10.*|127.*|169.254.*|192.168.*)                 return 0 ;;
        172.1[6-9].*|172.2[0-9].*|172.3[01].*)          return 0 ;;
        100.6[4-9].*|100.[7-9][0-9].*|100.1[01][0-9].*|100.12[0-7].*) return 0 ;;
        *) return 1 ;;
    esac
}

DB_ADDR=""
if [ "$SECMAN_DEV_DB_MODE" = host ]; then
    DB_ADDR=$SECMAN_DEV_DB_HOST
    if [ -z "$DB_ADDR" ]; then
        # The Mac is the container VM's default gateway. It cannot be named from
        # the host side (the Mac does not know which address the VM sees it on),
        # so it is resolved here, from inside, once.
        DB_ADDR=$(ip route show default 2>/dev/null | awk '/default/ {print $3; exit}')
        [ -n "$DB_ADDR" ] || die "could not determine this Mac's address from inside the container — start with --db-host <address>"
        log "database: the Mac is $DB_ADDR (the container's default gateway)"
    fi

    # A name is not what the firewall matches on — it matches addresses. Resolve
    # once, here, and use that address for both the rule and the JDBC URL, so the
    # two can never point at different hosts.
    case "$DB_ADDR" in
        [0-9]*.[0-9]*.[0-9]*.[0-9]*) ;;
        *)
            resolved=$(getent ahostsv4 "$DB_ADDR" 2>/dev/null | awk '{print $1; exit}')
            [ -n "$resolved" ] || die "could not resolve --db-host '$DB_ADDR' to an IPv4 address"
            log "database: --db-host $DB_ADDR resolves to $resolved"
            DB_ADDR=$resolved ;;
    esac
    printf '%s\n' "$DB_ADDR" | grep -qE '^([0-9]{1,3}\.){3}[0-9]{1,3}$' \
        || die "database host '$DB_ADDR' is not an IPv4 address"

    # --db host exists to reach the developer's own machine. Letting it point at
    # a public address would turn a convenience flag into a general 3306 egress
    # hole, which is precisely what this container is here to prevent.
    if ! is_local_v4 "$DB_ADDR" && [ "${SECMAN_DEV_DB_ALLOW_PUBLIC:-0}" != "1" ]; then
        die "refusing to open egress to $DB_ADDR:$SECMAN_DEV_DB_PORT — --db host is for the database on your Mac, and $DB_ADDR is not a private address. Set SECMAN_DEV_DB_ALLOW_PUBLIC=1 if that is really what you want."
    fi
fi

# devctl starts the in-container server on 3306 and the host script publishes
# that port; a different SECMAN_DEV_DB_PORT here would build a URL nothing
# listens on, which surfaces as an unexplained connection refusal much later.
if [ "$SECMAN_DEV_DB_MODE" = container ] && [ "$SECMAN_DEV_DB_PORT" != "3306" ]; then
    die "the in-container database is fixed on port 3306 (got SECMAN_DEV_DB_PORT=$SECMAN_DEV_DB_PORT) — --db-port applies to '--db host'"
fi

case "$SECMAN_DEV_DB_MODE" in
    container) DB_ADDR=127.0.0.1
               DB_URL="jdbc:mariadb://127.0.0.1:${SECMAN_DEV_DB_PORT}/${SECMAN_DEV_DB_NAME}${SECMAN_DEV_DB_PARAMS}" ;;
    host)      DB_URL="jdbc:mariadb://${DB_ADDR}:${SECMAN_DEV_DB_PORT}/${SECMAN_DEV_DB_NAME}${SECMAN_DEV_DB_PARAMS}" ;;
    none)      DB_URL="" ;;
esac

# Values are single-quoted because the JDBC URL may carry a query string, and an
# unquoted `&` in a file that /etc/profile.d sources would background the line
# instead of assigning it. Every value here has already been through a character
# allowlist above, none of which admits a quote, so the quoting cannot be broken
# from the outside.
umask 022
cat > "$DB_ENV" <<ENV
# Written by the container entrypoint at start-up. Do not edit — it is rebuilt
# on every start, and the firewall rule was built from these same values.
SECMAN_DEV_DB_MODE='$SECMAN_DEV_DB_MODE'
SECMAN_DEV_DB_HOST='$DB_ADDR'
SECMAN_DEV_DB_PORT='$SECMAN_DEV_DB_PORT'
SECMAN_DEV_DB_NAME='$SECMAN_DEV_DB_NAME'
SECMAN_DEV_DB_URL='$DB_URL'
ENV
chmod 0644 "$DB_ENV"

case "$SECMAN_DEV_DB_MODE" in
    container) log "database: in-container MariaDB — $DB_URL" ;;
    host)      log "database: the Mac's MariaDB — $DB_URL (one egress rule opens exactly this address and port)" ;;
    none)      log "database: none started — DB_CONNECT stays whatever pass-cli resolves" ;;
esac

# -----------------------------------------------------------------------------
# 3. Render the egress allowlist
# -----------------------------------------------------------------------------
# Rendering is a separate script because `devctl egress refresh` has to produce
# byte-identical output later, and because the firewall and every inspection
# command must read exactly one list rather than each re-parsing the sources.
ALLOWED_COUNT=$(/usr/local/sbin/render-egress-allowlist "$ALLOWLIST_RENDERED") \
    || die "could not render the egress allowlist — refusing to start with an unusable policy"
log "egress allowlist: $ALLOWED_COUNT domains"

# -----------------------------------------------------------------------------
# 4. Firewall
# -----------------------------------------------------------------------------
ALLOWLIST_RENDERED="$ALLOWLIST_RENDERED" /usr/local/sbin/init-egress-firewall

# -----------------------------------------------------------------------------
# 5. Prove the firewall
# -----------------------------------------------------------------------------
# A firewall nobody tested is a firewall nobody has. Two probes: one host that
# must work and one that must not.
#
# The negative probe is the load-bearing one — if a host nobody allowlisted is
# reachable then the containment is not there, and starting anyway would hand the
# agents an environment whose shielding is a guess. That one is fatal. The
# positive probe only warns: a momentarily unreachable GitHub is a network
# problem, not a policy failure.
#
# `--max-time 8` on the denied probe because a REJECT answers immediately; if it
# takes longer than that, something is silently dropping instead, which is worth
# knowing but is still a refusal.
if [ "${SECMAN_DEV_SKIP_EGRESS_SELFTEST:-0}" != "1" ]; then
    allowed_code=$(curl -sS --max-time 20 -o /dev/null -w '%{http_code}' https://api.github.com/meta 2>/dev/null || echo 000)
    denied_out=$(curl -sS --max-time 8 -o /dev/null https://www.google.com/ 2>&1 || true)
    denied_code=$(curl -sS --max-time 8 -o /dev/null -w '%{http_code}' https://www.google.com/ 2>/dev/null || echo 000)
    case "$allowed_code" in
        2*|3*|4*) log "egress self-test: allowlisted host reachable (HTTP $allowed_code)" ;;
        *)        warn "egress self-test: allowlisted host unreachable — check DNS and the allowlist ('egress-check')" ;;
    esac
    if [ "$denied_code" = "000" ]; then
        log "egress self-test: non-allowlisted host refused by the firewall"
    else
        die "egress self-test FAILED: a non-allowlisted host answered with HTTP $denied_code. The firewall is not containing this container. Refusing to start. ($denied_out)"
    fi
fi

# -----------------------------------------------------------------------------
# 6. Optional services
# -----------------------------------------------------------------------------
if [ "$SECMAN_DEV_DB_MODE" = "container" ]; then
    devctl db start || warn "MariaDB failed to start — run 'devctl db start' for the log"
elif [ "$SECMAN_DEV_DB_MODE" = "host" ]; then
    # A warning, not a failure: the developer may well start their Mac's MariaDB
    # after the container. But the two ways this silently fails later — the
    # server bound to 127.0.0.1 only, or no grant for the container's subnet —
    # look like an application bug from inside, so say it now.
    if nc -z -w 3 "$DB_ADDR" "$SECMAN_DEV_DB_PORT" >/dev/null 2>&1; then
        log "database: $DB_ADDR:$SECMAN_DEV_DB_PORT accepts connections"
    else
        warn "the Mac's MariaDB at $DB_ADDR:$SECMAN_DEV_DB_PORT is not answering."
        warn "  - is it running?            brew services start mariadb"
        warn "  - is it bound to all interfaces, not just 127.0.0.1?   bind-address = 0.0.0.0"
        warn "  - does the user have a grant for this container?       CREATE USER '<user>'@'$(printf '%s' "$DB_ADDR" | cut -d. -f1-3).%' ..."
        warn "  re-check from inside with: devctl db status"
    fi
fi
if [ "$SECMAN_DEV_WITH_TLS" = "1" ]; then
    devctl tls start || warn "nginx failed to start — run 'devctl tls start' for the log"
fi

# -----------------------------------------------------------------------------
# 7. Keep the allow-set fresh
# -----------------------------------------------------------------------------
# Filtering by address means the policy is a snapshot of DNS, and the services
# this container depends on are CDN-hosted: registry.npmjs.org, api.anthropic.com
# and repo.maven.apache.org all move between addresses within hours. Without this
# loop a container left running overnight starts failing downloads for reasons
# that look nothing like a firewall. With ipset the update is an atomic swap, so
# a refresh never interrupts traffic; with plain rules there is a sub-second
# window in which new connections are refused (established ones are unaffected).
REFRESH_MINUTES=${SECMAN_EGRESS_REFRESH_MINUTES:-30}
if [ "$REFRESH_MINUTES" -gt 0 ] 2>/dev/null; then
    (
        while :; do
            sleep $((REFRESH_MINUTES * 60))
            /usr/local/sbin/refresh-egress >>/var/log/secman-egress-refresh.log 2>&1 \
                || echo "[egress] refresh failed — previous ruleset still in effect" >&2
        done
    ) &
    REFRESHER_PID=$!
    log "allow-set refresh every ${REFRESH_MINUTES}m (SECMAN_EGRESS_REFRESH_MINUTES=0 disables)"
else
    REFRESHER_PID=
    log "allow-set refresh disabled — run 'devctl egress refresh' when a download starts failing"
fi

# -----------------------------------------------------------------------------
# 8. Hand over
# -----------------------------------------------------------------------------
shutdown() {
    log "shutting down"
    [ -n "$REFRESHER_PID" ] && kill "$REFRESHER_PID" 2>/dev/null || true
    devctl tls stop >/dev/null 2>&1 || true
    devctl db stop  >/dev/null 2>&1 || true
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
