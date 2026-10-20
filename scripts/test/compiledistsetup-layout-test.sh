#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

fake_repo="$tmp_dir/repo"
mkdir -p "$fake_repo/scripts" "$fake_repo/src/frontend" "$tmp_dir/bin"
cp "$repo_root/scripts/compiledistsetup.sh" "$fake_repo/scripts/compiledistsetup.sh"
chmod +x "$fake_repo/scripts/compiledistsetup.sh"

cat > "$fake_repo/gradlew" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p src/backendng/build/libs src/cli/build/libs
: > src/backendng/build/libs/backendng-0.1-all.jar
: > src/cli/build/libs/cli-0.1.0-all.jar
STUB
chmod +x "$fake_repo/gradlew"

cat > "$tmp_dir/bin/npm" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "run" && "${2:-}" == "build" ]]; then
  mkdir -p dist/client/_astro dist/server
  echo 'new bundle' > dist/client/_astro/current.js
  echo 'new server' > dist/server/entry.mjs
fi
STUB
chmod +x "$tmp_dir/bin/npm"

cat > "$tmp_dir/bin/ssh" <<'STUB'
#!/usr/bin/env bash
printf 'ssh %s\n' "$*" >> "${CAPTURED_COMMANDS:?}"
STUB
chmod +x "$tmp_dir/bin/ssh"

cat > "$tmp_dir/bin/rsync" <<'STUB'
#!/usr/bin/env bash
printf 'rsync %s\n' "$*" >> "${CAPTURED_COMMANDS:?}"
args=()
while [[ $# -gt 1 ]]; do
  if [[ "$1" == "-e" ]]; then shift 2; else args+=("$1"); shift; fi
done
host="${1%%:*}"
remote_path="${1#*:}"
"${REAL_RSYNC:?}" --rsync-path="${REAL_RSYNC:?}" "${args[@]}" "${TEST_HOSTS:?}/$host$remote_path"
STUB
chmod +x "$tmp_dir/bin/rsync"

export REAL_RSYNC="$(command -v rsync)"
export TEST_HOSTS="$tmp_dir/hosts"
for host in ssm-user@host-a ssm-user@host-b; do
  root="$TEST_HOSTS/$host/opt/secman/app"
  mkdir -p "$root/src/backendng/build/libs" "$root/src/cli/build/libs" \
    "$root/src/frontend/dist/client/_astro" "$root/src/frontend/dist/server"
  echo 'previous bundle' > "$root/src/frontend/dist/client/_astro/previous.js"
  echo 'obsolete server' > "$root/src/frontend/dist/server/obsolete.mjs"
done

export CAPTURED_COMMANDS="$tmp_dir/commands.log"
PATH="$tmp_dir/bin:$PATH" "$fake_repo/scripts/compiledistsetup.sh" \
  ssm-user@host-a ssm-user@host-b --dest /opt/secman/app

commands="$(cat "$CAPTURED_COMMANDS")"

for expected in \
  "/opt/secman/app/src/backendng/build/libs" \
  "/opt/secman/app/src/cli/build/libs" \
  "/opt/secman/app/src/frontend/dist"
do
  case "$commands" in
    *"$expected"*) ;;
    *)
      echo "Expected compiledistsetup.sh to deploy to $expected" >&2
      echo "$commands" >&2
      exit 1
      ;;
  esac
done

case "$commands" in
  *"/opt/secman/app/backend"*|*"/opt/secman/app/cli"*|*"/opt/secman/app/frontend"*)
    echo "compiledistsetup.sh still deploys to the old flat layout" >&2
    echo "$commands" >&2
    exit 1
    ;;
esac

for host in ssm-user@host-a ssm-user@host-b; do
  dist="$TEST_HOSTS/$host/opt/secman/app/src/frontend/dist"
  [[ "$(cat "$dist/client/_astro/previous.js")" == 'previous bundle' ]]
  [[ "$(cat "$dist/client/_astro/current.js")" == 'new bundle' ]]
  [[ "$(cat "$dist/server/entry.mjs")" == 'new server' ]]
  [[ ! -e "$dist/server/obsolete.mjs" ]]
done

# Check publishing order: assets must be available before the server manifest changes.
python3 - "$CAPTURED_COMMANDS" <<'CHECK'
import sys
from pathlib import Path
commands = Path(sys.argv[1]).read_text().splitlines()
for host in ('ssm-user@host-a', 'ssm-user@host-b'):
    transfers = [line for line in commands if line.startswith('rsync ') and host in line and 'frontend/dist' in line]
    assert len(transfers) == 2, transfers
    assert '/client/_astro/' in transfers[0] and '--delete' not in transfers[0], transfers
    assert "--exclude=/client/_astro/" in transfers[1], transfers
CHECK

echo 'Deployment regression passed: previous and current bundles coexist; obsolete server files are removed.'
