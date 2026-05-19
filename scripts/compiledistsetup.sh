#!/usr/bin/env bash
#
# compiledistsetup.sh - build secman locally and ship artifacts to two hosts.
#
# Full reference: docs/COMPILE_DIST_SETUP.md
#
# SYNOPSIS
#   ./scripts/compiledistsetup.sh <user1@host1> <user2@host2> [--dest <remote-dir>]
#   ./scripts/compiledistsetup.sh -h | --help
#
# ARGUMENTS
#   <user1@host1>           First SSH target, in user@host form (required).
#   <user2@host2>           Second SSH target, in user@host form (required).
#                           Exactly two targets must be supplied; bare
#                           hostnames (no '@') are rejected.
#
# OPTIONS
#   --dest <remote-dir>     Remote directory on both targets. Default:
#                           "secman" (relative to the SSH user's $HOME).
#                           Absolute paths are honoured.
#   -h, --help              Show this help and exit.
#
# WHAT IT BUILDS
#   - Backend  : ./gradlew :backendng:clean :backendng:shadowJar -x test
#                -> src/backendng/build/libs/backendng-<ver>-all.jar
#   - CLI      : ./gradlew :cli:clean :cli:shadowJar -x test
#                -> src/cli/build/libs/cli-<ver>-all.jar
#   - Frontend : npm ci && npm run build  (in src/frontend)
#                -> src/frontend/dist/
#
#   Tests are skipped for deploy speed. Run ./gradlew build separately if
#   you want full coverage before shipping.
#
# REMOTE LAYOUT (created under <remote-dir> on each target)
#   <remote-dir>/
#     src/backendng/build/libs/backendng-<ver>-all.jar
#     src/cli/build/libs/cli-<ver>-all.jar
#     src/frontend/dist/        (contents of src/frontend/dist/)
#
#   Transfer uses rsync -az --delete for the frontend dist directory.
#   Backend and CLI jars are copied into their Gradle build/libs locations
#   so runtime scripts that reference the repository-like tree keep working.
#
# PREREQUISITES
#   Build host : bash, ssh, rsync, npm, JDK 21 toolchain, ./gradlew.
#   Each target: sshd reachable on port 22, rsync installed, build host's
#                public key in ~<user>/.ssh/authorized_keys, write access
#                to <remote-dir>.
#
#   For non-default ports, jump hosts, or specific identity files, add an
#   entry to ~/.ssh/config -- the script does not expose flags for these.
#
# SSH OPTIONS USED
#   -o BatchMode=yes                  (no password/passphrase prompts)
#   -o StrictHostKeyChecking=accept-new
#   -o ConnectTimeout=15
#
# BEHAVIOUR
#   Targets are processed sequentially. If the first target fails, the
#   script exits and the second is not touched. There is no roll-back.
#
# EXIT CODES
#   0  Build + both deploys succeeded.
#   1  Bad args, missing tool, build failure, missing artifact, or
#      ssh/rsync failure.
#
# EXAMPLES
#   ./scripts/compiledistsetup.sh \
#       deploy@app01.example.com deploy@app02.example.com
#
#   ./scripts/compiledistsetup.sh \
#       alice@10.0.0.10 bob@10.0.0.11 --dest /opt/secman
#
#   ./scripts/compiledistsetup.sh \
#       ops@stage-a ops@stage-b --dest releases/2026-05-17

set -euo pipefail

usage() {
    sed -n '2,75p' "$0" | sed 's/^# \{0,1\}//;s/^#$//'
    exit "${1:-1}"
}

# ---- Arg parsing -------------------------------------------------------------

REMOTE_DEST="secman"   # relative to remote $HOME by default
TARGETS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dest)
            [[ $# -ge 2 ]] || { echo "Error: --dest requires a value" >&2; usage 1; }
            REMOTE_DEST="$2"
            shift 2
            ;;
        -h|--help)
            usage 0
            ;;
        -*)
            echo "Error: unknown option: $1" >&2
            usage 1
            ;;
        *)
            TARGETS+=("$1")
            shift
            ;;
    esac
done

if [[ ${#TARGETS[@]} -ne 2 ]]; then
    echo "Error: exactly two targets required (got ${#TARGETS[@]})" >&2
    usage 1
fi

for t in "${TARGETS[@]}"; do
    if [[ "$t" != *@* ]]; then
        echo "Error: target '$t' is not in user@host form" >&2
        exit 1
    fi
done

# ---- Locate repo root --------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ---- Tool checks -------------------------------------------------------------

require() {
    command -v "$1" >/dev/null 2>&1 || { echo "Error: required tool '$1' not found in PATH" >&2; exit 1; }
}

require ssh
require rsync
require npm

GRADLE_CMD="./gradlew"
[[ -x "$GRADLE_CMD" ]] || { echo "Error: $GRADLE_CMD not executable in $REPO_ROOT" >&2; exit 1; }

# ---- Build -------------------------------------------------------------------

echo "==> Building backend (shadowJar)"
"$GRADLE_CMD" :backendng:clean :backendng:shadowJar -x test

echo "==> Building CLI (shadowJar)"
"$GRADLE_CMD" :cli:clean :cli:shadowJar -x test

echo "==> Building frontend (npm ci + build)"
(
    cd src/frontend
    npm ci
    npm run build
)

# ---- Locate artifacts --------------------------------------------------------

BACKEND_JAR="$(ls -1 "$REPO_ROOT"/src/backendng/build/libs/backendng-*-all.jar 2>/dev/null | head -n 1 || true)"
CLI_JAR="$(ls -1 "$REPO_ROOT"/src/cli/build/libs/cli-*-all.jar 2>/dev/null | head -n 1 || true)"
FRONTEND_DIST="$REPO_ROOT/src/frontend/dist"

[[ -f "$BACKEND_JAR" ]] || { echo "Error: backend shadowJar not found under src/backendng/build/libs/" >&2; exit 1; }
[[ -f "$CLI_JAR" ]]     || { echo "Error: cli shadowJar not found under src/cli/build/libs/" >&2; exit 1; }
[[ -d "$FRONTEND_DIST" ]] || { echo "Error: frontend dist not found at $FRONTEND_DIST" >&2; exit 1; }

echo "==> Build complete:"
echo "    backend : $BACKEND_JAR"
echo "    cli     : $CLI_JAR"
echo "    frontend: $FRONTEND_DIST"

# ---- Distribute --------------------------------------------------------------

SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
RSYNC_SSH="ssh ${SSH_OPTS[*]}"

deploy_to() {
    local target="$1"
    echo "==> Deploying to $target:$REMOTE_DEST"

    local remote_backend_libs="$REMOTE_DEST/src/backendng/build/libs"
    local remote_cli_libs="$REMOTE_DEST/src/cli/build/libs"
    local remote_frontend_dist="$REMOTE_DEST/src/frontend/dist"

    # Pre-flight: confirm SSH works and create remote layout
    ssh "${SSH_OPTS[@]}" "$target" "mkdir -p '$remote_backend_libs' '$remote_cli_libs' '$remote_frontend_dist'"

    rsync -az --delete -e "$RSYNC_SSH" \
        "$BACKEND_JAR" "$target:$remote_backend_libs/"

    rsync -az --delete -e "$RSYNC_SSH" \
        "$CLI_JAR" "$target:$remote_cli_libs/"

    rsync -az --delete -e "$RSYNC_SSH" \
        "$FRONTEND_DIST/" "$target:$remote_frontend_dist/"

    echo "    done: $target"
}

for target in "${TARGETS[@]}"; do
    deploy_to "$target"
done

echo "==> All targets updated."
