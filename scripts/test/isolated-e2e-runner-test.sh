#!/usr/bin/env bash
# Exercise database-only credential propagation without a vault or real database.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/scripts/test" "$TEST_DIR/bin"
cp "$REPO_ROOT/scripts/test/run-isolated-e2e.sh" "$TEST_DIR/scripts/test/"
cat > "$TEST_DIR/bin/mariadb" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
    *'SELECT owner_token'*)
        jq -r '.ownerToken' "$TEST_DIR"/.e2e-logs/isolated-manifests/*.json ;;
esac
STUB
for tool in mariadb-dump pass-cli rsync lsof; do
    printf '#!/usr/bin/env bash\nexit 0\n' > "$TEST_DIR/bin/$tool"
done
chmod +x "$TEST_DIR/bin/"*
export TEST_DIR PATH="$TEST_DIR/bin:$PATH" SECMAN_E2E_SECRETS_READY=1
export SECMAN_ADMIN_NAME=runner-admin SECMAN_ADMIN_EMAIL=runner-admin@example.test
export SECMAN_ADMIN_PASS="$(openssl rand -hex 24)"
# Stale bootstrap values must be replaced with this run's vault credentials.
export SECMAN_E2E_ADMIN_NAME=stale SECMAN_E2E_ADMIN_EMAIL=stale
export SECMAN_E2E_ADMIN_PASS="$(openssl rand -hex 24)"
bash "$TEST_DIR/scripts/test/run-isolated-e2e.sh" --database-only -- bash -c '
    set -euo pipefail
    [[ "$SECMAN_E2E_ADMIN_NAME" == "$SECMAN_ADMIN_NAME" ]]
    [[ "$SECMAN_E2E_ADMIN_EMAIL" == "$SECMAN_ADMIN_EMAIL" ]]
    [[ "$SECMAN_E2E_ADMIN_PASS" == "$SECMAN_ADMIN_PASS" ]]
    [[ "$TEST_DB_URL" == "$DB_CONNECT" && "$TEST_DB_USERNAME" == "$SECMAN_TEST_ISOLATED_DB" ]]
'
[[ -z "$(find "$TEST_DIR/.e2e-logs/isolated-manifests" -name '*.json' -print)" ]]
echo "Database-only runner: bootstrap credentials, database scope, and manifest cleanup passed"
