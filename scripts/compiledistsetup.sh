#!/usr/bin/env bash
#
# compiledistsetup.sh
#
# Build secman (backend, CLI, frontend) and distribute the artifacts to two
# target hosts via SSH. SSH key-based auth to both targets is assumed.
#
# Usage:
#   ./scripts/compiledistsetup.sh <user1@host1> <user2@host2> [--dest <remote-dir>]
#
# Examples:
#   ./scripts/compiledistsetup.sh deploy@app01.example.com deploy@app02.example.com
#   ./scripts/compiledistsetup.sh alice@10.0.0.10 bob@10.0.0.11 --dest /opt/secman
#
# Remote layout created under <remote-dir> (default: ~/secman):
#   backend/backendng-<version>-all.jar
#   cli/cli-<version>-all.jar
#   frontend/                          (contents of src/frontend/dist/)

set -euo pipefail

usage() {
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
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

    # Pre-flight: confirm SSH works and create remote layout
    ssh "${SSH_OPTS[@]}" "$target" "mkdir -p '$REMOTE_DEST/backend' '$REMOTE_DEST/cli' '$REMOTE_DEST/frontend'"

    rsync -az --delete -e "$RSYNC_SSH" \
        "$BACKEND_JAR" "$target:$REMOTE_DEST/backend/"

    rsync -az --delete -e "$RSYNC_SSH" \
        "$CLI_JAR" "$target:$REMOTE_DEST/cli/"

    rsync -az --delete -e "$RSYNC_SSH" \
        "$FRONTEND_DIST/" "$target:$REMOTE_DEST/frontend/"

    echo "    done: $target"
}

for target in "${TARGETS[@]}"; do
    deploy_to "$target"
done

echo "==> All targets updated."
