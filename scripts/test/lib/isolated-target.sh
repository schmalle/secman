#!/usr/bin/env bash
# Source and call secman_test_require_isolated before database-mutating E2E work.
secman_test_require_isolated() {
    local expected_db="${SECMAN_TEST_ISOLATED_DB:-}" token="${SECMAN_TEST_OWNER_TOKEN:-}"
    local actual_token
    if [[ ! "$expected_db" =~ ^secman_e2e_[a-f0-9]{16}$ || ! "$token" =~ ^[a-f0-9]{32}$ ]]; then
        echo "This test requires scripts/test/run-isolated-e2e.sh" >&2
        return 2
    fi
    if [[ "${DB_NAME:-}" != "$expected_db" ||
          "${DB_CONNECT:-}" != "jdbc:mariadb://127.0.0.1:3306/$expected_db" ||
          "${BASE_URL:-}" != "${SECMAN_E2E_BACKEND_URL:-}" ||
          "${SECMAN_E2E_BACKEND_URL:-}" != http://127.0.0.1:18080 ]]; then
        echo "Test target does not match its disposable database and backend" >&2
        return 2
    fi
    if [[ -z "${DB_USER:-}" || -z "${DB_PASS:-}" ]]; then
        echo "Disposable database credentials are missing" >&2
        return 2
    fi
    actual_token="$(MYSQL_PWD="$DB_PASS" mariadb --batch --skip-column-names \
        -h 127.0.0.1 -u "$DB_USER" "$expected_db" \
        -e 'SELECT owner_token FROM secman_e2e_owner LIMIT 1' 2>/dev/null)" || return 2
    if [[ "$actual_token" != "$token" ]]; then
        echo "Disposable database ownership marker does not match" >&2
        return 2
    fi
}
