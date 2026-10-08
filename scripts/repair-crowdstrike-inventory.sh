#!/usr/bin/env bash
# Local, offline repair of import-time contact stamps using recorded Falcon evidence.
# Defaults to a read-only preview. Connection uses the operator's MariaDB client defaults.
set -euo pipefail
mode="${1:---preview}"
database="${SECMAN_REPAIR_DATABASE:-secman}"
[[ "$database" =~ ^[A-Za-z0-9_]+$ ]] || { echo 'Invalid database name' >&2; exit 2; }
case "$mode" in --preview|--apply|--rollback) ;; *) echo 'Usage: repair-crowdstrike-inventory.sh [--preview|--apply|--rollback RUN_UUID]' >&2; exit 2;; esac
if [[ "$mode" != --preview ]]; then
    if lsof -n -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1 || lsof -n -iTCP:18080 -sTCP:LISTEN >/dev/null 2>&1; then
        echo 'Stop the backend before applying or rolling back this offline repair.' >&2
        exit 1
    fi
fi
client=(mariadb --batch --skip-column-names --database "$database")
if [[ -n "${SECMAN_TEST_ISOLATED_DB:-}" ]]; then
    export DB_NAME="$SECMAN_TEST_ISOLATED_DB" DB_USER="$SECMAN_TEST_DB_USERNAME" DB_PASS="$SECMAN_TEST_DB_PASSWORD"
    export BASE_URL="$SECMAN_E2E_BACKEND_URL"
    source "$(dirname "$0")/test/lib/isolated-target.sh"
    secman_test_require_isolated
    [[ "$database" == "$DB_NAME" ]] || { echo 'Repair target differs from isolated database' >&2; exit 2; }
    client+=(-h 127.0.0.1 -u "$DB_USER")
    export MYSQL_PWD="$DB_PASS"
fi
sql() { "${client[@]}" -e "$1"; }
if [[ "$mode" == --rollback ]]; then
    run_id="${2:-}"
    [[ "$run_id" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo 'Valid run UUID required' >&2; exit 2; }
    sql "START TRANSACTION;
        UPDATE asset a JOIN crowdstrike_contact_repair_audit r ON r.asset_id=a.id
        SET a.crowdstrike_agent_seen_at=r.before_seen_at, r.rolled_back_at=UTC_TIMESTAMP(6)
        WHERE r.run_id='$run_id' AND r.rolled_back_at IS NULL
          AND a.crowdstrike_agent_seen_at <=> r.after_seen_at;
        SELECT ROW_COUNT(); COMMIT;"
    echo 'Rolled back only rows still matching the repair result; newer observations are preserved.'
    exit 0
fi
# Multiple current bindings are ambiguous and deliberately excluded.
candidates="SELECT a.id, a.crowdstrike_agent_seen_at AS before_seen_at,
    i.falcon_last_seen_at AS after_seen_at
    FROM asset a JOIN crowdstrike_asset_identity i ON i.asset_id=a.id
    WHERE i.falcon_last_seen_at IS NOT NULL AND i.falcon_last_seen_at <= UTC_TIMESTAMP(6)
      AND NOT (a.crowdstrike_agent_seen_at <=> i.falcon_last_seen_at)
      AND NOT EXISTS (SELECT 1 FROM crowdstrike_asset_identity other WHERE other.asset_id=a.id AND other.id<>i.id)"
if [[ "$mode" == --preview ]]; then
    sql "SELECT COUNT(*) AS repairable_contact_stamps FROM ($candidates) candidates;
        SELECT JSON_OBJECT('assetId', id, 'before', before_seen_at, 'after', after_seen_at) FROM ($candidates) candidates ORDER BY id;"
    exit 0
fi
run_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
# Each batch commits its before/after audit and corresponding update atomically.
while true; do
    changed="$(sql "START TRANSACTION;
        INSERT INTO crowdstrike_contact_repair_audit
            (run_id, asset_id, before_seen_at, after_seen_at, repaired_at)
        SELECT '$run_id', c.id, c.before_seen_at, c.after_seen_at, UTC_TIMESTAMP(6)
        FROM ($candidates ORDER BY a.id LIMIT 500) c;
        UPDATE asset a JOIN crowdstrike_contact_repair_audit r ON r.asset_id=a.id
        SET a.crowdstrike_agent_seen_at=r.after_seen_at
        WHERE r.run_id='$run_id' AND a.crowdstrike_agent_seen_at <=> r.before_seen_at;
        SELECT ROW_COUNT(); COMMIT;")"
    [[ "$changed" == 0 ]] && break
done
printf 'Repair run: %s\n' "$run_id"
sql "SELECT COUNT(*) AS repaired_assets FROM crowdstrike_contact_repair_audit WHERE run_id='$run_id';"
