#!/usr/bin/env bash
set -euo pipefail
export DB_NAME="${SECMAN_TEST_ISOLATED_DB:-}" DB_USER="${SECMAN_TEST_DB_USERNAME:-}" DB_PASS="${SECMAN_TEST_DB_PASSWORD:-}"
export BASE_URL="${SECMAN_E2E_BACKEND_URL:-}"
source "$(dirname "$0")/lib/isolated-target.sh"
secman_test_require_isolated
export SECMAN_REPAIR_DATABASE="$DB_NAME" MYSQL_PWD="$DB_PASS"
sql() { mariadb --batch --skip-column-names -h 127.0.0.1 -u "$DB_USER" "$DB_NAME" -e "$1"; }
# The database-only runner starts empty; these tables model the repair's exact columns.
sql "CREATE TABLE asset (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), type VARCHAR(255),
    owner VARCHAR(255), crowdstrike_agent_seen_at DATETIME(6));
    CREATE TABLE crowdstrike_asset_identity (id BIGINT AUTO_INCREMENT PRIMARY KEY, asset_id BIGINT NOT NULL,
    crowdstrike_aid VARCHAR(64), source_hostname VARCHAR(255), first_seen_at DATETIME(6), last_seen_at DATETIME(6),
    falcon_last_seen_at DATETIME(6), FOREIGN KEY (asset_id) REFERENCES asset(id));"
# Database-only runner: apply the two new audit tables if the source schema predates them.
if [[ "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='crowdstrike_contact_repair_audit'")" == 0 ]]; then
    mariadb -h 127.0.0.1 -u "$DB_USER" "$DB_NAME" < src/backendng/src/main/resources/db/migration/V284__crowdstrike_inventory_repair_audit.sql
fi
sql "INSERT INTO asset (name,type,owner,crowdstrike_agent_seen_at) VALUES
    ('repair-confirmed','SERVER','CrowdStrike Import','2026-10-08 09:40:07'),
    ('repair-unknown','SERVER','CrowdStrike Import','2026-10-08 09:40:07'),
    ('repair-ambiguous','SERVER','CrowdStrike Import','2026-10-08 09:40:07');
    INSERT INTO crowdstrike_asset_identity (asset_id,crowdstrike_aid,source_hostname,first_seen_at,last_seen_at,falcon_last_seen_at)
    SELECT id,CONCAT('repair-',id),name,NOW(),NOW(),IF(name='repair-unknown',NULL,'2026-09-12 01:02:03') FROM asset WHERE name LIKE 'repair-%';
    INSERT INTO crowdstrike_asset_identity (asset_id,crowdstrike_aid,source_hostname,first_seen_at,last_seen_at,falcon_last_seen_at)
    SELECT id,'repair-other',name,NOW(),NOW(),'2026-09-13 01:02:03' FROM asset WHERE name='repair-ambiguous';"
preview="$(./scripts/repair-crowdstrike-inventory.sh --preview)"
[[ "${preview%%$'\n'*}" == 1 ]]
[[ "$(sql 'SELECT COUNT(*) FROM crowdstrike_contact_repair_audit')" == 0 ]]
result="$(./scripts/repair-crowdstrike-inventory.sh --apply)"
run_id="$(printf '%s\n' "$result" | sed -n 's/^Repair run: //p')"
[[ "$(sql "SELECT COUNT(*) FROM asset WHERE name='repair-confirmed' AND crowdstrike_agent_seen_at='2026-09-12 01:02:03'")" == 1 ]]
./scripts/repair-crowdstrike-inventory.sh --apply >/dev/null
[[ "$(sql 'SELECT COUNT(*) FROM crowdstrike_contact_repair_audit')" == 1 ]]
./scripts/repair-crowdstrike-inventory.sh --rollback "$run_id" >/dev/null
[[ "$(sql "SELECT COUNT(*) FROM asset WHERE name LIKE 'repair-%' AND crowdstrike_agent_seen_at='2026-10-08 09:40:07'")" == 3 ]]
echo 'PASS: read-only preview, evidence-only repair, idempotency, and rollback'
