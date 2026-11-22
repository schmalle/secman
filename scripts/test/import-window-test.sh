#!/usr/bin/env bash
# Verify wrapper arguments without a vault, JVM or backend.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/scripts/lib" "$FIXTURE/src/cli/build/libs" "$FIXTURE/bin"
cp "$ROOT/scripts/import.sh" "$ROOT/scripts/importaws.sh" "$FIXTURE/scripts/"
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
export CROWDSTRIKE_IMPORT_LAST_SEEN_DAYS="${MOCK_VAULT_DAYS:-1}"
export CROWDSTRIKE_SENSOR_RETENTION_DAYS="${MOCK_VAULT_RETENTION:-0}"
exec "$@"
MOCK
chmod +x "$FIXTURE/bin/java" "$FIXTURE/bin/pass-cli"
cat > "$FIXTURE/scripts/lib/aws-secrets.sh" <<'MOCK'
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
secman_aws_require_cli_jar() { test -f "$PROJECT_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"; }
secman_aws_export_envfile() {
    export CROWDSTRIKE_IMPORT_LAST_SEEN_DAYS="${MOCK_VAULT_DAYS:-1}"
    export CROWDSTRIKE_SENSOR_RETENTION_DAYS="${MOCK_VAULT_RETENTION:-0}"
}
MOCK
for wrapper in import.sh importaws.sh; do
    for configured in 0 1 2 3 7 30; do
        actual=$(PATH="$FIXTURE/bin:$PATH" MOCK_VAULT_DAYS="$configured" bash "$FIXTURE/scripts/$wrapper")
        [[ "$actual" == 1 ]] || { echo "$wrapper: window $configured returned $actual" >&2; exit 1; }
    done
    actual=$(PATH="$FIXTURE/bin:$PATH" MOCK_VAULT_RETENTION=1 bash "$FIXTURE/scripts/$wrapper")
    [[ "$actual" == 1 ]] || exit 1
    if PATH="$FIXTURE/bin:$PATH" MOCK_VAULT_DAYS=invalid bash "$FIXTURE/scripts/$wrapper" >/dev/null 2>&1; then
        echo "$wrapper: invalid discovery window was accepted" >&2; exit 1
    fi
done
echo 'import-window-test: 16 cases passed'
