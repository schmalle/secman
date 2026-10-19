#!/usr/bin/env bash
# Verify wrapper arguments without a vault, JVM or backend.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/scripts" "$FIXTURE/src/cli/build/libs" "$FIXTURE/bin"
cp "$ROOT/scripts/import.sh" "$FIXTURE/scripts/import.sh"
touch "$FIXTURE/src/cli/build/libs/cli-0.1.0-all.jar" "$FIXTURE/secmanpp.env"
cat > "$FIXTURE/bin/java" <<'MOCK'
#!/usr/bin/env bash
while (( $# )); do
    if [[ "$1" == "--last-seen-days" ]]; then printf '%s\n' "$2"; exit 0; fi
    shift
done
exit 1
MOCK
cat > "$FIXTURE/bin/pass-cli" <<'MOCK'
#!/usr/bin/env bash
while [[ "$1" != "--" ]]; do shift; done
shift
# Simulate settings that become available only after vault resolution.
export CROWDSTRIKE_IMPORT_LAST_SEEN_DAYS="${MOCK_VAULT_DAYS:-3}"
export CROWDSTRIKE_SENSOR_RETENTION_DAYS="${MOCK_VAULT_RETENTION:-0}"
exec "$@"
MOCK
chmod +x "$FIXTURE/bin/java" "$FIXTURE/bin/pass-cli"
for pair in '3:3' '7:3' '0:3' '2:2'; do
    configured="${pair%%:*}" expected="${pair##*:}"
    actual=$(PATH="$FIXTURE/bin:$PATH" MOCK_VAULT_DAYS="$configured" bash "$FIXTURE/scripts/import.sh")
    [[ "$actual" == "$expected" ]] || { echo "window $configured returned $actual" >&2; exit 1; }
done
actual=$(PATH="$FIXTURE/bin:$PATH" MOCK_VAULT_DAYS=7 MOCK_VAULT_RETENTION=1 bash "$FIXTURE/scripts/import.sh")
[[ "$actual" == "1" ]] || exit 1
if PATH="$FIXTURE/bin:$PATH" MOCK_VAULT_DAYS=invalid bash "$FIXTURE/scripts/import.sh" >/dev/null 2>&1; then
    echo "Invalid discovery window was accepted" >&2; exit 1
fi
echo 'import-window-test: 6 cases passed'
