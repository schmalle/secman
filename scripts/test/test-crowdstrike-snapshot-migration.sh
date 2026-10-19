#!/usr/bin/env bash
# Run through run-isolated-e2e.sh --database-only; never against application data.
set -euo pipefail
source "$(dirname "$0")/lib/isolated-target.sh"
secman_test_require_isolated
export MYSQL_PWD="$DB_PASS"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MIGRATION="$REPO_ROOT/src/backendng/src/main/resources/db/migration/V286__crowdstrike_snapshot_provenance.sql"

sql() { mariadb --batch --skip-column-names -h 127.0.0.1 -u "$DB_USER" "$DB_NAME" -e "$1"; }
migrate() { mariadb -h 127.0.0.1 -u "$DB_USER" "$DB_NAME" < "$MIGRATION"; }
assert_equal() {
    if [[ "$1" != "$2" ]]; then
        echo "FAIL: $3 (expected '$2', got '$1')" >&2
        exit 1
    fi
}
assert_schema() {
    assert_equal "$(sql "SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='vulnerability'
        AND column_name='crowdstrike_snapshot_id' AND data_type='bigint' AND is_nullable='YES'")" 1 'nullable snapshot reference'
    assert_equal "$(sql "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
        FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='crowdstrike_snapshot' AND index_name='idx_cs_snapshot_scope'")" \
        'asset_id,severity,active' 'snapshot scope index'
    assert_equal "$(sql "SELECT column_name FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='vulnerability'
        AND index_name='idx_vulnerability_cs_snapshot'")" crowdstrike_snapshot_id 'vulnerability index'
}

# Model only V286's pre-existing dependencies in the runner's empty schema.
sql "CREATE TABLE vulnerability (id BIGINT NOT NULL PRIMARY KEY);
    CREATE TABLE vulnerability_statistics_cache (cache_key VARCHAR(255) NOT NULL PRIMARY KEY);
    INSERT INTO vulnerability VALUES (7);
    INSERT INTO vulnerability_statistics_cache VALUES ('aws_clean_server_kpi'), ('unrelated');"
migrate
assert_schema
assert_equal "$(sql 'SELECT crowdstrike_snapshot_id IS NULL FROM vulnerability WHERE id=7')" 1 'legacy finding stays unverified'
assert_equal "$(sql 'SELECT cache_key FROM vulnerability_statistics_cache')" unrelated 'targeted cache invalidation'
echo 'PASS: fresh migration'

sql "INSERT INTO crowdstrike_snapshot
    (id,asset_id,crowdstrike_aid,severity,observed_at,completed_at,complete,active)
    VALUES (11,42,'fixture-aid','HIGH','2026-10-01 12:00:00','2026-10-01 12:01:00',1,0);
    INSERT INTO crowdstrike_identity_lock VALUES ('fixture-lock');
    UPDATE vulnerability SET crowdstrike_snapshot_id=11 WHERE id=7;"
snapshot_before="$(sql 'SELECT * FROM crowdstrike_snapshot')"
migrate
assert_schema
assert_equal "$(sql 'SELECT * FROM crowdstrike_snapshot')" "$snapshot_before" 'existing snapshot preserved'
assert_equal "$(sql 'SELECT identity_key FROM crowdstrike_identity_lock')" fixture-lock 'existing lock preserved'
assert_equal "$(sql 'SELECT crowdstrike_snapshot_id FROM vulnerability WHERE id=7')" 11 'existing reference preserved'
echo 'PASS: existing objects and linked data preserved'

# Simulate a previous attempt that created only the snapshot table, without its index.
sql "DROP TABLE crowdstrike_identity_lock;
    DROP INDEX idx_cs_snapshot_scope ON crowdstrike_snapshot;
    ALTER TABLE vulnerability DROP INDEX idx_vulnerability_cs_snapshot,
        DROP COLUMN crowdstrike_snapshot_id;"
migrate
assert_schema
assert_equal "$(sql 'SELECT * FROM crowdstrike_snapshot')" "$snapshot_before" 'partial-state snapshot preserved'
assert_equal "$(sql 'SELECT COUNT(*) FROM crowdstrike_identity_lock')" 0 'missing lock table created'
assert_equal "$(sql 'SELECT crowdstrike_snapshot_id IS NULL FROM vulnerability WHERE id=7')" 1 'partial-state finding stays unverified'
echo 'PASS: partial schema completed without inventing provenance'

# Also cover an existing reference column with only its index missing.
sql 'ALTER TABLE vulnerability DROP INDEX idx_vulnerability_cs_snapshot;
    UPDATE vulnerability SET crowdstrike_snapshot_id=11 WHERE id=7;'
migrate
assert_schema
assert_equal "$(sql 'SELECT crowdstrike_snapshot_id FROM vulnerability WHERE id=7')" 11 'reference preserved while restoring index'
assert_equal "$(sql 'SELECT cache_key FROM vulnerability_statistics_cache')" unrelated 'unrelated cache entry preserved on retries'
echo 'PASS: missing reference index restored'
