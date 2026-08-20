#!/usr/bin/env bash
# secman-container.sh — drive the shielded Secman dev container on Apple silicon.
# =============================================================================
# Runs on macOS, on top of Apple's `container` CLI (github.com/apple/container).
# It builds and starts one long-lived container that
#
#   * sees exactly one host path — the source tree you pass in, and nothing else;
#   * publishes 8080 (backend), 4321 (frontend), 443 (TLS front door) and
#     3306 (MariaDB) back to the Mac;
#   * can reach only the addresses on the egress allowlist, enforced by an
#     iptables policy inside the container — there is no proxy;
#   * carries Claude Code, Kimi CLI and pass-cli, so the agents run inside the
#     shield rather than on your Mac.
#
#   ./scripts/container/secman-container.sh build
#   ./scripts/container/secman-container.sh up --src ~/src/secman
#   ./scripts/container/secman-container.sh claude
#
# Run `... help` for the full command list.
# =============================================================================
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
IMAGE_CONTEXT="$REPO_ROOT/docker/apple-container"

IMAGE=${SECMAN_DEV_IMAGE:-secman-dev:latest}
NAME=${SECMAN_DEV_NAME:-secman-dev}

# Gradle's daemon is configured for a 5632m heap (gradle.properties) and KSP runs
# inside it, so anything under ~8g turns `./gradlew build` into an OOM loop.
MEMORY=${SECMAN_DEV_MEMORY:-10g}
CPUS=${SECMAN_DEV_CPUS:-6}

TLS_PORT=${SECMAN_DEV_TLS_PORT:-443}
TLS_HOST=${SECMAN_DEV_TLS_HOST:-localhost}
WITH_DB=1
WITH_TLS=1
EXTRA_DOMAINS=${SECMAN_EGRESS_EXTRA_DOMAINS:-}
SRC=""

VOL_HOME="${NAME}-home"     # agent config, pass-cli session, Gradle/npm caches
VOL_DB="${NAME}-db"         # MariaDB data directory

BLUE=$'\033[38;5;39m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; DIM=$'\033[2m'; OFF=$'\033[0m'
log()  { printf '%s==>%s %s\n' "$BLUE" "$OFF" "$*"; }
warn() { printf '%swarning:%s %s\n' "$YELLOW" "$OFF" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }
note() { printf '%s    %s%s\n' "$DIM" "$*" "$OFF"; }

# -----------------------------------------------------------------------------
# Preflight — everything Apple-specific that goes wrong before the container
# even starts, checked once, with an actionable message instead of a stack trace.
# -----------------------------------------------------------------------------
preflight() {
    [ "$(uname -s)" = "Darwin" ] || die "this script targets macOS; Apple's \`container\` does not exist elsewhere"
    [ "$(uname -m)" = "arm64" ]  || die "Apple silicon required (\`container\` runs Linux VMs via Virtualization.framework)"
    command -v container >/dev/null 2>&1 \
        || die "\`container\` not found. Install it from https://github.com/apple/container/releases"

    # macOS 15 can run containers but has no port forwarding and heavily limited
    # container networking; the published-ports model below needs macOS 26.
    local major; major=$(sw_vers -productVersion | cut -d. -f1)
    if [ "$major" -lt 26 ]; then
        warn "macOS $major detected. Apple's \`container\` only supports --publish on macOS 26+."
        warn "On macOS 15 the ports below will not be forwarded; reach the container on its own"
        warn "IP address instead (\`container ls\` shows it)."
    fi

    # The container system is a launchd service and is not running after a reboot.
    if ! container system status >/dev/null 2>&1; then
        log "starting the container system service"
        container system start
    fi
}

# -----------------------------------------------------------------------------
# Source path — the single host path the container is allowed to see.
# -----------------------------------------------------------------------------
resolve_src() {
    local candidate=${1:-$REPO_ROOT}
    [ -d "$candidate" ] || die "source path does not exist: $candidate"
    candidate=$(cd "$candidate" && pwd -P)

    # The whole point of this container is that one directory is reachable and
    # nothing else. Handing it a home directory or a volume root would share the
    # developer's entire filesystem with the agents and quietly defeat that, so
    # reject the parents outright rather than trusting the caller to notice.
    case "$candidate" in
        /|/Users|/Volumes|/System|/System/*|/Library|/Library/*|/private|/private/*|/etc|/var|/usr|/opt)
            die "refusing to share '$candidate' — pass the project directory itself, not a parent" ;;
    esac
    [ "$candidate" != "$HOME" ] || die "refusing to share your home directory — pass the project directory itself"

    [ -d "$candidate/.git" ] || warn "$candidate is not a git working tree — is that the source path you meant?"
    printf '%s\n' "$candidate"
}

# `container inspect` is the reliable probe here: the columns and JSON keys of
# `container ls` have moved between releases, but inspect either resolves the
# name or exits non-zero.
container_exists()  { container inspect "$NAME" >/dev/null 2>&1 ; }
container_running() { container inspect "$NAME" 2>/dev/null | grep -qi '"status"[[:space:]]*:[[:space:]]*"running"' ; }
require_running() {
    container_running || die "container '$NAME' is not running. Start it with: $0 up --src <path>"
}
container_ip() {
    container inspect "$NAME" 2>/dev/null \
        | grep -oE '"address"[[:space:]]*:[[:space:]]*"[0-9.]+' \
        | head -1 | grep -oE '[0-9.]+$' || true
}

# Can this user bind a privileged port on the Mac? `container`'s port-forwarding
# helper runs as the same user, so this answers whether --publish 443 will work.
can_bind_privileged_port() {
    perl -MSocket -e '
        socket(S, PF_INET, SOCK_STREAM, 0) or exit 2;
        setsockopt(S, SOL_SOCKET, SO_REUSEADDR, 1);
        bind(S, sockaddr_in('"$1"', INADDR_LOOPBACK)) or exit 1;
        exit 0;' 2>/dev/null
}

# -----------------------------------------------------------------------------
# build
# -----------------------------------------------------------------------------
cmd_build() {
    preflight
    # BuildKit runs in its own container; the default 2 CPUs / small memory make
    # this image's downloads and unpacks needlessly slow.
    container builder start --cpus 4 --memory 8g >/dev/null 2>&1 || true
    log "building $IMAGE"
    note "context: $IMAGE_CONTEXT (the repository is bind-mounted at run time, never copied in)"
    container build --tag "$IMAGE" --file "$IMAGE_CONTEXT/Containerfile" "$@" "$IMAGE_CONTEXT"
    log "built $IMAGE"
}

# -----------------------------------------------------------------------------
# up
# -----------------------------------------------------------------------------
cmd_up() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --src)          SRC=$2; shift 2 ;;
            --name)         NAME=$2; VOL_HOME="${NAME}-home"; VOL_DB="${NAME}-db"; shift 2 ;;
            --memory|-m)    MEMORY=$2; shift 2 ;;
            --cpus|-c)      CPUS=$2; shift 2 ;;
            --tls-host)     TLS_HOST=$2; shift 2 ;;
            --tls-port)     TLS_PORT=$2; shift 2 ;;
            --with-db)      WITH_DB=1; shift ;;
            --no-db)        WITH_DB=0; shift ;;
            --no-tls)       WITH_TLS=0; shift ;;
            --allow-domain) EXTRA_DOMAINS="${EXTRA_DOMAINS:+$EXTRA_DOMAINS,}$2"; shift 2 ;;
            *)              die "unknown option for 'up': $1" ;;
        esac
    done

    preflight
    SRC=$(resolve_src "$SRC")

    container image inspect "$IMAGE" >/dev/null 2>&1 \
        || die "image $IMAGE not found — run: $0 build"

    if container_exists; then
        if container_running; then
            log "container '$NAME' is already running"; cmd_status; return 0
        fi
        log "removing the stopped container '$NAME' (its volumes are kept)"
        container rm "$NAME" >/dev/null
    fi

    # Named volumes rather than host directories: caches and agent state have to
    # survive a restart, but none of them needs to be visible on the Mac — and
    # every extra bind mount widens exactly the exposure this container exists to
    # narrow. They are also sparse ext4 images, so Gradle and npm stop paying the
    # virtiofs cost they would on a shared directory.
    container volume create "$VOL_HOME" >/dev/null 2>&1 || true
    if [ "$WITH_DB" -eq 1 ]; then container volume create "$VOL_DB" >/dev/null 2>&1 || true; fi

    local publish_tls=$TLS_PORT
    if [ "$TLS_PORT" -lt 1024 ] && ! can_bind_privileged_port "$TLS_PORT"; then
        publish_tls=8443
        warn "this account cannot bind host port $TLS_PORT (macOS reserves ports below 1024 for root),"
        warn "so :443 in the container is published on the Mac as :$publish_tls instead."
        warn "The container still serves TLS on 443 — reach it there directly on the container IP,"
        warn "or re-run with --tls-port $publish_tls to make the mapping explicit."
    fi

    log "starting '$NAME'"
    note "source   : $SRC  ->  /workspace   (the only host path shared)"
    note "memory   : $MEMORY   cpus: $CPUS"
    note "ports    : 8080 backend · 4321 frontend · ${publish_tls}->443 TLS · 3306 mariadb"

    # --publish and every other option must precede the image name; `container`
    # treats anything after it as arguments for the container process.
    local -a args=(
        run --detach --name "$NAME"
        --cpus "$CPUS" --memory "$MEMORY"
        --init
        # NET_ADMIN/NET_RAW are what let the entrypoint install its own egress
        # policy. They are added to the default restricted set, not on top of a
        # privileged container — `container` has no privileged mode.
        --cap-add NET_ADMIN --cap-add NET_RAW
        --volume "$SRC:/workspace"
        --volume "$VOL_HOME:/home/dev"
        --publish "8080:8080"
        --publish "4321:4321"
        --publish "${publish_tls}:443"
        --env "SECMAN_DEV_WITH_TLS=$WITH_TLS"
        --env "SECMAN_DEV_WITH_DB=$WITH_DB"
        --env "SECMAN_DEV_TLS_HOST=$TLS_HOST"
        --env "SECMAN_EGRESS_EXTRA_DOMAINS=$EXTRA_DOMAINS"
    )
    if [ "$WITH_DB" -eq 1 ]; then
        args+=(--volume "$VOL_DB:/var/lib/mysql" --publish "3306:3306")
    fi
    # A Proton Pass personal access token, if the host shell exports one, so the
    # first `pass-cli` call inside works without an interactive login. Omitted
    # entirely when unset — an empty value would look like a broken credential.
    if [ -n "${PROTON_PASS_PERSONAL_ACCESS_TOKEN:-}" ]; then
        args+=(--env "PROTON_PASS_PERSONAL_ACCESS_TOKEN=$PROTON_PASS_PERSONAL_ACCESS_TOKEN")
        note "pass-cli : personal access token passed through from your shell"
    fi
    args+=("$IMAGE")

    container "${args[@]}" >/dev/null

    # The entrypoint refuses to start if its egress self-test fails, so a
    # container that is not running a few seconds in means the shield did not
    # come up — show the reason rather than a bare "not running".
    local i=0
    while [ $i -lt 60 ]; do
        container_running || break
        container logs "$NAME" 2>/dev/null | grep -q 'ready —' && break
        sleep 1; i=$((i + 1))
    done
    if ! container_running; then
        printf '%s\n' "$(container logs "$NAME" 2>&1 | tail -30)" >&2
        die "'$NAME' exited during start-up (see the log above)"
    fi

    cmd_status
    printf '\n'
    log "next steps"
    note "$0 shell            # a login shell inside the shield"
    note "$0 claude           # Claude Code, inside the shield"
    note "$0 kimi             # Kimi CLI, inside the shield"
    note "then, from /workspace: ./scripts/startbackenddev.sh and ./scripts/startfrontenddev.sh"
}

# -----------------------------------------------------------------------------
# exec-style commands
# -----------------------------------------------------------------------------
# Everything runs as `dev`, never root: the firewall and its allowlist are
# root-owned, so an agent session cannot dismantle the shield it runs in.
in_container() {
    require_running
    container exec --interactive --tty --user dev --workdir /workspace "$NAME" "$@"
}
cmd_shell()  { in_container bash -l ; }
cmd_claude() { in_container bash -lc 'exec claude "$@"' -- "$@" ; }
cmd_kimi()   { in_container bash -lc 'exec kimi "$@"'   -- "$@" ; }
cmd_run()    { [ "$#" -gt 0 ] || die "run needs a command"; in_container bash -lc 'exec "$@"' -- "$@" ; }
cmd_root()   {
    require_running
    if [ "$#" -eq 0 ]; then set -- bash -l; fi
    container exec --interactive --tty --user root --workdir /workspace "$NAME" "$@"
}

# -----------------------------------------------------------------------------
# status / logs / down
# -----------------------------------------------------------------------------
cmd_status() {
    if ! container_running; then
        printf "container '%s': not running\n" "$NAME"; return 1
    fi
    local ip; ip=$(container_ip)
    printf "container '%s': running%s\n" "$NAME" "${ip:+  (ip $ip)}"
    container exec --user dev "$NAME" bash -lc 'devctl status' 2>/dev/null || true
    if [ -n "$ip" ]; then
        printf '\n'
        note "direct, unpublished routes (useful when a host port could not be bound):"
        note "  https://$ip/        · http://$ip:4321  · http://$ip:8080  · $ip:3306"
    fi
}
cmd_logs()  { require_running; container logs ${1:+--follow} "$NAME" ; }
cmd_down()  {
    container_exists || { log "container '$NAME' does not exist"; return 0; }
    log "stopping '$NAME'"
    container stop "$NAME" >/dev/null 2>&1 || true
    container rm   "$NAME" >/dev/null 2>&1 || true
    note "volumes $VOL_HOME and $VOL_DB are kept — '$0 destroy' removes them too"
}
cmd_destroy() {
    cmd_down
    log "removing volumes"
    container volume delete "$VOL_HOME" >/dev/null 2>&1 || true
    container volume delete "$VOL_DB"   >/dev/null 2>&1 || true
    note "the agent sessions, the pass-cli login and the build caches are gone"
}

# -----------------------------------------------------------------------------
# egress
# -----------------------------------------------------------------------------
cmd_egress() {
    require_running
    case "${1:-show}" in
        show)       container exec --user dev  "$NAME" bash -lc 'egress-check' ;;
        log)        container exec --user root "$NAME" bash -lc "egress-check --log ${2:-40}" ;;
        unresolved) container exec --user dev  "$NAME" bash -lc 'egress-check --unresolved' ;;
        refresh)    container exec --user root "$NAME" bash -lc 'refresh-egress' ;;
        test)       shift; container exec --user dev "$NAME" bash -lc 'exec egress-check "$@"' -- "$@" ;;
        *)          die "usage: $0 egress [show|log [N]|unresolved|refresh|test <host>...]" ;;
    esac
}

usage() {
    cat <<USAGE
secman-container.sh — shielded Secman dev environment on Apple \`container\`

  build [container-build-args...]   build the dev image
  up   [options]                    start the shielded container
  shell                             login shell inside it (user: dev)
  claude [args...]                  run Claude Code inside it
  kimi   [args...]                  run Kimi CLI inside it
  run <cmd...>                      run one command inside it
  root [cmd...]                     root shell inside it (for devctl)
  status                            services, ports and egress mode
  logs [-f]                         container start-up log
  egress [show|log|refresh|test]   inspect or rebuild the egress policy
  down                              stop and remove the container, keep volumes
  destroy                           down, and delete the volumes too

Options for 'up':
  --src PATH        source tree to share at /workspace (default: this repository).
                    This is the ONLY host path the container can see.
  --name NAME       container name (default: $NAME)
  --memory SIZE     default $MEMORY — Gradle's daemon alone is configured for 5632m
  --cpus N          default $CPUS
  --no-db           do not run the in-container MariaDB 11.4 (:3306 stays closed).
                    Use this when DB_CONNECT from pass-cli points at a database
                    that already exists elsewhere.
  --no-tls          do not start the :443 TLS front door
  --tls-host HOST   certificate subject / SAN (default: $TLS_HOST)
  --tls-port PORT   host port for the container's :443 (default: $TLS_PORT)
  --allow-domain D  add D to the egress allowlist (repeatable). Exact hostnames
                    only — filtering is by address, so a wildcard matches nothing

Environment: SECMAN_DEV_IMAGE, SECMAN_DEV_NAME, SECMAN_DEV_MEMORY, SECMAN_DEV_CPUS,
SECMAN_DEV_TLS_HOST, SECMAN_DEV_TLS_PORT, SECMAN_EGRESS_EXTRA_DOMAINS,
PROTON_PASS_PERSONAL_ACCESS_TOKEN.

Full documentation: docs/APPLE_CONTAINER_DEV.md
USAGE
}

cmd=${1:-help}; shift || true
case "$cmd" in
    build)   cmd_build "$@" ;;
    up|start) cmd_up "$@" ;;
    shell|sh) cmd_shell ;;
    claude)  cmd_claude "$@" ;;
    kimi)    cmd_kimi "$@" ;;
    run)     cmd_run "$@" ;;
    root)    cmd_root "$@" ;;
    status)  cmd_status ;;
    logs)    cmd_logs "${1:-}" ;;
    egress)  cmd_egress "$@" ;;
    down|stop) cmd_down ;;
    destroy) cmd_destroy ;;
    help|-h|--help) usage ;;
    *)       usage; exit 2 ;;
esac
