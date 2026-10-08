#!/usr/bin/env bash
# Exercises migrated MariaDB foreign keys, which Hibernate-only tests omit.
# Usage: ./scripts/test/run-isolated-e2e.sh -- bash scripts/test/test-asset-bulk-delete.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/isolated-target.sh"
secman_test_require_isolated

python3 - <<'PY'
import http.cookies
import json
import os
import subprocess
import urllib.error
import urllib.request


def sql(statement):
    result = subprocess.run(
        ["mariadb", "--batch", "--skip-column-names", "-h", "127.0.0.1",
         "-u", os.environ["DB_USER"], os.environ["DB_NAME"]],
        input=statement, text=True, capture_output=True,
        env={**os.environ, "MYSQL_PWD": os.environ["DB_PASS"]}, check=True,
    )
    return result.stdout.strip()


def request(method, path, data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(
        os.environ["BASE_URL"] + path,
        data=json.dumps(data).encode() if data is not None else None,
        headers=headers, method=method,
    )
    try:
        response = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


assert sql("SELECT COUNT(*) FROM asset") == "0", "Requires an empty disposable inventory"
status, headers, _ = request("POST", "/api/auth/login", {
    "username": os.environ["SECMAN_ADMIN_NAME"].strip(),
    "password": os.environ["SECMAN_ADMIN_PASS"].strip(),
})
assert status == 200, f"Admin login failed: HTTP {status}"
cookies = http.cookies.SimpleCookie()
for value in headers.get_all("Set-Cookie", []):
    cookies.load(value)
token = cookies["secman_auth"].value

sql("""
INSERT INTO asset (name, type, owner) VALUES ('bulk-delete-regression', 'SERVER', 'e2e');
SET @asset = LAST_INSERT_ID();
INSERT INTO asset (name, type, owner) VALUES ('bulk-delete-second', 'SERVER', 'e2e');
INSERT INTO asset_tag (asset_id, tag_key, tag_value) VALUES (@asset, 'purpose', 'regression');
INSERT INTO vulnerability (asset_id, scan_timestamp, vulnerability_id)
VALUES (@asset, NOW(), 'CVE-E2E-BULK');
SET @vulnerability = LAST_INSERT_ID();
INSERT INTO integration_scanner (name, source, service_user_id)
SELECT 'bulk-delete-scanner', 'WEB', id FROM users ORDER BY id LIMIT 1;
SET @scanner = LAST_INSERT_ID();
INSERT INTO integration_subject (scanner_id, asset_id, created_at)
VALUES (@scanner, @asset, NOW());
SET @subject = LAST_INSERT_ID();
INSERT INTO integration_run
(scanner_id, subject_id, run_key, content_digest, status, complete_coverage,
 started_at, completed_at, accepted, resolved, metadata_json, findings_json, inventory_json)
VALUES (@scanner, @subject, 'bulk-delete-run', REPEAT('a', 64), 'SUCCESS', TRUE,
 NOW(), NOW(), 1, 0, '{}', '[]', 'null');
SET @run = LAST_INSERT_ID();
INSERT INTO integration_finding
(subject_id, external_id, severity, state, title, first_seen_at, last_seen_at,
 vulnerability_id, projection_key, last_run_id)
VALUES (@subject, 'bulk-delete-finding', 'HIGH', 'OPEN', 'Regression', NOW(), NOW(),
 @vulnerability, 'bulk-delete-projection', @run);
INSERT INTO integration_attachment (finding_id, run_id, file_name, content_type, content)
VALUES (LAST_INSERT_ID(), @run, 'evidence.txt', 'text/plain', 'regression');
INSERT INTO web_exposure
(subject_id, last_run_id, configured_url, reachability, redirect_count, vantage_point, observed_at)
VALUES (@subject, @run, 'https://example.test', 'REACHABLE', 0, 'e2e', NOW());
INSERT INTO web_component
(subject_id, component_key, category, name, confidence, evidence_type, evidence,
 state, first_seen_at, last_seen_at, last_run_id)
VALUES (@subject, 'bulk-delete-component', 'SERVER', 'fixture', 1, 'HEADER', 'fixture',
 'OPEN', NOW(), NOW(), @run);
""")

tables = (
    "asset", "asset_tag", "vulnerability", "integration_subject", "integration_run",
    "integration_finding", "integration_attachment", "web_exposure", "web_component",
)


def counts():
    # Table names are a closed fixture list, never request input.
    return {table: int(sql(f"SELECT COUNT(*) FROM {table}")) for table in tables}


before = counts()
scanner_count = sql("SELECT COUNT(*) FROM integration_scanner")
status, _, _ = request("DELETE", "/api/assets/bulk")
assert status in (401, 403), f"Anonymous deletion was not rejected: HTTP {status}"
assert counts() == before
print("PASS: anonymous deletion rejected", flush=True)

# Force a late failure to prove child deletions roll back with the parent delete.
sql("""CREATE TRIGGER bulk_delete_regression_failure BEFORE DELETE ON asset
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'bulk deletion rollback test'""")
try:
    status, _, body = request("DELETE", "/api/assets/bulk", token=token)
    assert status == 500, f"Forced failure returned HTTP {status}"
    assert json.loads(body)["error"] == "Bulk delete failed. No assets were deleted."
    assert counts() == before, "Failed deletion did not roll back all children"
finally:
    sql("DROP TRIGGER bulk_delete_regression_failure")
print("PASS: failed deletion rolls back all records", flush=True)

status, _, body = request("DELETE", "/api/assets/bulk", token=token)
assert status == 200, f"Bulk deletion failed: HTTP {status}: {body.decode()}"
result = json.loads(body)
assert result["deletedAssets"] == 2
assert result["deletedVulnerabilities"] == 1
assert result["deletedScanResults"] == 0
assert all(value == 0 for value in counts().values()), "Asset children remain"
assert sql("SELECT COUNT(*) FROM integration_scanner") == scanner_count, "Scanner configuration deleted"
print("PASS: populated integration tree and tagged assets deleted; scanner preserved", flush=True)

status, _, body = request("DELETE", "/api/assets/bulk", token=token)
assert status == 200 and json.loads(body)["deletedAssets"] == 0
print("PASS: repeated bulk deletion succeeds on empty inventory", flush=True)
PY
