#!/usr/bin/env bash
# Create a self-contained SecMan test fixture:
#
#   1. a fresh test user (role USER, unique per run)
#   2. a test system (asset) owned by that user
#   3. a HIGH vulnerability on that system
#   4. an exception request filed BY the test user, scoped to that one system
#
# The exception request is deliberately left PENDING: only ADMIN/SECCHAMPION
# requests auto-approve, and the test user is a plain USER.
#
# This manual fixture remains until scripts/manual/cleanup-test-data.sh is run
# with its exact ownership manifest. Automated tests use disposable databases.
#
# All credentials and the host URL come from Proton Pass via pass-cli.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

# When invoked directly, set up pass:// templates and re-exec under pass-cli.
# On the second invocation pass-cli has resolved the env vars, so we skip this
# block to avoid clobbering the resolved values back to literals.
if [[ -z "${SECMAN_HOST_RESOLVED:-}" ]]; then
    export SECMAN_HOST="pass://Test/SECMAN/SECMAN_HOST"
    export SECMAN_ADMIN_NAME="pass://Test/SECMAN/SECMAN_ADMIN_NAME"
    export SECMAN_ADMIN_PASS="pass://Test/SECMAN/SECMAN_ADMIN_PASS"
    export SECMAN_USER_PASS="pass://Test/SECMAN/SECMAN_USER_PASS"
    export SECMAN_HOST_RESOLVED=1
    exec pass-cli run -- "$0" "$@"
fi

: "${SECMAN_HOST:?SECMAN_HOST not resolved by pass-cli}"
: "${SECMAN_ADMIN_NAME:?SECMAN_ADMIN_NAME not resolved}"
: "${SECMAN_ADMIN_PASS:?SECMAN_ADMIN_PASS not resolved}"
: "${SECMAN_USER_PASS:?SECMAN_USER_PASS not resolved}"

command -v jq >/dev/null || { echo "✗ jq is required" >&2; exit 1; }

# SECMAN_HOST in the vault is the bare hostname — assume https unless it
# already carries a scheme.
if [[ "$SECMAN_HOST" == http://* || "$SECMAN_HOST" == https://* ]]; then
    BASE_URL="${SECMAN_HOST%/}"
else
    BASE_URL="https://${SECMAN_HOST%/}"
fi

STAMP="$(date +%Y%m%d-%H%M%S)-$(openssl rand -hex 4)"
TEST_USER="testdata-user-${STAMP}"
TEST_EMAIL="${TEST_USER}@e2e.local"
TEST_ASSET="testdata-host-${STAMP}"
MANIFEST_DIR="$REPO_ROOT/.e2e-logs/manual-fixtures"
MANIFEST_FILE="$MANIFEST_DIR/$STAMP.json"
mkdir -p "$MANIFEST_DIR"
jq -n --arg target "$BASE_URL" --arg user "$TEST_USER" --arg email "$TEST_EMAIL" \
    --arg asset "$TEST_ASSET" \
    '{target:$target,userName:$user,userEmail:$email,assetName:$asset}' > "$MANIFEST_FILE"

record_manifest_id() {
    local field="$1" id="$2" temp
    [[ "$id" =~ ^[0-9]+$ ]] || { echo "Missing numeric $field; inspect $MANIFEST_FILE" >&2; exit 1; }
    temp="$(mktemp "$MANIFEST_FILE.XXXXXX")"
    jq --arg field "$field" --argjson id "$id" '.[$field]=$id' "$MANIFEST_FILE" > "$temp"
    mv "$temp" "$MANIFEST_FILE"
}
TEST_CVE="CVE-2021-44228"
TEST_SEVERITY="HIGH"
DAYS_OPEN=45
EXPIRES_AT="$(date -v+30d +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -d '+30 days' +%Y-%m-%dT%H:%M:%S)"

ADMIN_JAR="$(mktemp)"
USER_JAR="$(mktemp)"
BODY="$(mktemp)"
trap 'rm -f "$ADMIN_JAR" "$USER_JAR" "$BODY"' EXIT

# login <jar> <username> <password> — establishes the HttpOnly session cookie.
login() {
    local jar="$1" user="$2" pass="$3" status
    status=$(curl -sS -o /dev/null -w '%{http_code}' -c "$jar" \
        -H 'Content-Type: application/json' \
        -X POST "${BASE_URL}/api/auth/login" \
        --data "$(jq -nc --arg u "$user" --arg p "$pass" '{username:$u,password:$p}')")
    [[ "$status" == "200" ]] || { echo "✗ Login failed for '$user' (HTTP $status)" >&2; return 1; }
}

# post <jar> <path> <json> <expected-status...> — body lands in $BODY.
post() {
    local jar="$1" path="$2" payload="$3"; shift 3
    local status; status=$(curl -sS -o "$BODY" -w '%{http_code}' -b "$jar" \
        -H 'Content-Type: application/json' \
        -X POST "${BASE_URL}${path}" --data "$payload")
    local ok
    for ok in "$@"; do [[ "$status" == "$ok" ]] && return 0; done
    echo "✗ POST $path failed (HTTP $status):" >&2
    cat "$BODY" >&2; echo >&2
    return 1
}

echo "→ Target: ${BASE_URL}"
echo "→ Logging in as admin …"
login "$ADMIN_JAR" "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS"

echo "→ [1/4] Creating test user '${TEST_USER}' (role USER) …"
post "$ADMIN_JAR" /api/users \
    "$(jq -nc --arg u "$TEST_USER" --arg e "$TEST_EMAIL" --arg p "$SECMAN_USER_PASS" \
        '{username:$u,email:$e,password:$p,roles:["USER"]}')" 200 201
USER_ID=$(jq -r '.id // empty' "$BODY")
record_manifest_id userId "$USER_ID"
echo "  ✓ user id=${USER_ID:-?}"

# owner = the test user's username so the asset resolves under the unified
# asset-access rule "owner == username" and is visible to them in the UI.
echo "→ [2/4] Creating test system '${TEST_ASSET}' owned by '${TEST_USER}' …"
post "$ADMIN_JAR" /api/assets \
    "$(jq -nc --arg n "$TEST_ASSET" --arg o "$TEST_USER" \
        '{name:$n,type:"Server",ip:"10.99.0.1",owner:$o,
          description:"Synthetic fixture created by scripts/test/create-test-data.sh"}')" 200 201
ASSET_ID=$(jq -r '.id' "$BODY")
record_manifest_id assetId "$ASSET_ID"
echo "  ✓ asset id=${ASSET_ID}"

echo "→ [3/4] Adding ${TEST_SEVERITY} vulnerability ${TEST_CVE} to '${TEST_ASSET}' …"
post "$ADMIN_JAR" /api/vulnerabilities/cli-add \
    "$(jq -nc --arg h "$TEST_ASSET" --arg c "$TEST_CVE" --arg s "$TEST_SEVERITY" --argjson d "$DAYS_OPEN" \
        '{hostname:$h,cve:$c,criticality:$s,daysOpen:$d}')" 200
VULN_ID=$(jq -r '.id' "$BODY")
record_manifest_id vulnerabilityId "$VULN_ID"
ASSET_CREATED=$(jq -r '.assetCreated' "$BODY")
if [[ "$ASSET_CREATED" != "false" ]]; then
    echo "✗ cli-add created a second asset instead of reusing '${TEST_ASSET}'" >&2
    exit 1
fi
echo "  ✓ vulnerability id=${VULN_ID} (attached to existing asset, not a duplicate)"

echo "→ [4/4] Logging in as '${TEST_USER}' and requesting the exception …"
login "$USER_JAR" "$TEST_USER" "$SECMAN_USER_PASS"

# subject=CVE × scope=ASSET == "this CVE, on this one system only".
# scopeValue MUST be null for scope=ASSET; assetId carries the target instead.
# reason must be 50-2048 characters or the API rejects with 400.
post "$USER_JAR" /api/vulnerability-exception-requests \
    "$(jq -nc --argjson v "$VULN_ID" --arg c "$TEST_CVE" --argjson a "$ASSET_ID" \
              --arg h "$TEST_ASSET" --arg x "$EXPIRES_AT" \
        '{vulnerabilityId:$v,subject:"CVE",subjectValue:$c,scope:"ASSET",assetId:$a,
          reason:("Test fixture: compensating controls are in place on " + $h +
                  " and the affected component is not reachable from untrusted networks, " +
                  "so remediation is deferred pending the next maintenance window."),
          expirationDate:$x}')" 200 201
REQ_ID=$(jq -r '.id' "$BODY")
record_manifest_id requestId "$REQ_ID"
REQ_STATUS=$(jq -r '.status' "$BODY")
echo "  ✓ exception request id=${REQ_ID} status=${REQ_STATUS}"

if [[ "$REQ_STATUS" != "PENDING" ]]; then
    echo "✗ Expected PENDING (plain USER must not auto-approve), got '${REQ_STATUS}'" >&2
    exit 1
fi

cat <<EOF

────────────────────────────────────────────────────────────
✓ Test data created on ${BASE_URL}

  User                 ${TEST_USER}  (id=${USER_ID:-?}, role USER)
  Password             = SECMAN_USER_PASS in Proton Pass
  System (asset)       ${TEST_ASSET}  (id=${ASSET_ID}, owner=${TEST_USER})
  Vulnerability        ${TEST_CVE} ${TEST_SEVERITY}, ${DAYS_OPEN}d open (id=${VULN_ID})
  Exception request    id=${REQ_ID}, ${REQ_STATUS}
                       subject=CVE(${TEST_CVE}) × scope=ASSET(${ASSET_ID})
                       expires ${EXPIRES_AT}

  Approve/reject it as admin at ${BASE_URL}/exception-requests
  Manual fixture retained. Cleanup: ./scripts/manual/cleanup-test-data.sh $MANIFEST_FILE
────────────────────────────────────────────────────────────
EOF
