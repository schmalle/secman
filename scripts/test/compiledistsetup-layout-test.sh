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
  mkdir -p dist/client dist/server
  : > dist/server/entry.mjs
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
STUB
chmod +x "$tmp_dir/bin/rsync"

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
