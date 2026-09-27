#!/usr/bin/env bash
# Remove one explicitly retained manual fixture recorded by create-test-data.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST_DIR="$REPO_ROOT/.e2e-logs/manual-fixtures"
[[ $# -eq 1 ]] || { echo "Usage: $0 <fixture-manifest.json>" >&2; exit 2; }
MANIFEST="$(realpath "$1")"
[[ "$MANIFEST" == "$MANIFEST_DIR/"* && -f "$MANIFEST" ]] || {
    echo "Manifest must be a recorded manual fixture under $MANIFEST_DIR" >&2; exit 2;
}

if [[ -z "${SECMAN_HOST_RESOLVED:-}" ]]; then
    export SECMAN_HOST="pass://Test/SECMAN/SECMAN_HOST"
    export SECMAN_ADMIN_NAME="pass://Test/SECMAN/SECMAN_ADMIN_NAME"
    export SECMAN_ADMIN_PASS="pass://Test/SECMAN/SECMAN_ADMIN_PASS"
    export SECMAN_USER_PASS="pass://Test/SECMAN/SECMAN_USER_PASS"
    export SECMAN_HOST_RESOLVED=1
    exec pass-cli run -- "$0" "$MANIFEST"
fi

for name in SECMAN_HOST SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS SECMAN_USER_PASS; do
    [[ -n "${!name:-}" ]] || { echo "Missing $name" >&2; exit 2; }
done
BASE_URL="$SECMAN_HOST"
[[ "$BASE_URL" == http://* || "$BASE_URL" == https://* ]] || BASE_URL="https://$BASE_URL"
BASE_URL="${BASE_URL%/}"

target="$(jq -r '.target // empty' "$MANIFEST")"
[[ "$target" == "$BASE_URL" ]] || {
    echo "Fixture target differs from the current SecMan host; refusing cleanup" >&2; exit 2;
}

user_name="$(jq -r '.userName // empty' "$MANIFEST")"
user_email="$(jq -r '.userEmail // empty' "$MANIFEST")"
asset_name="$(jq -r '.assetName // empty' "$MANIFEST")"
user_id="$(jq -r '.userId // empty' "$MANIFEST")"
asset_id="$(jq -r '.assetId // empty' "$MANIFEST")"
request_id="$(jq -r '.requestId // empty' "$MANIFEST")"
[[ "$user_name" == testdata-user-* && "$asset_name" == testdata-host-* ]] || {
    echo "Manifest lacks manual fixture identity" >&2; exit 2;
}

admin_jar="$(mktemp)"
user_jar="$(mktemp)"
response_file="$(mktemp)"
trap 'rm -f "$admin_jar" "$user_jar" "$response_file"' EXIT

login() {
    local jar="$1" name="$2" password="$3" status
    status="$(curl -sS -o "$response_file" -w '%{http_code}' -c "$jar" \
        -H 'Content-Type: application/json' -X POST "$BASE_URL/api/auth/login" \
        --data "$(jq -nc --arg u "$name" --arg p "$password" '{username:$u,password:$p}')")"
    [[ "$status" == 200 ]] || { echo "Login failed (HTTP $status)" >&2; return 1; }
}

get_status() {
    curl -sS -o "$response_file" -w '%{http_code}' -b "$1" "$BASE_URL$2"
}

delete_status() {
    curl -sS -o "$response_file" -w '%{http_code}' -b "$1" -X DELETE "$BASE_URL$2"
}

login "$admin_jar" "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS"

if [[ "$request_id" =~ ^[0-9]+$ ]]; then
    status="$(get_status "$admin_jar" "/api/vulnerability-exception-requests/$request_id")"
    if [[ "$status" == 200 ]]; then
        jq -e --argjson id "$request_id" --argjson user "$user_id" \
            --argjson asset "$asset_id" --arg name "$user_name" \
            '.id==$id and .requestedByUserId==$user and .assetId==$asset and .requestedByUsername==$name' \
            "$response_file" >/dev/null || {
                echo "Exception request identity mismatch; preserving it" >&2; exit 1;
            }
        login "$user_jar" "$user_name" "$SECMAN_USER_PASS"
        status="$(delete_status "$user_jar" "/api/vulnerability-exception-requests/$request_id/delete")"
        [[ "$status" == 204 ]] || { echo "Request cleanup failed (HTTP $status)" >&2; exit 1; }
    elif [[ "$status" != 404 ]]; then
        echo "Request lookup failed (HTTP $status)" >&2; exit 1
    fi
fi

if [[ "$asset_id" =~ ^[0-9]+$ ]]; then
    status="$(get_status "$admin_jar" "/api/assets/$asset_id")"
    if [[ "$status" == 200 ]]; then
        jq -e --argjson id "$asset_id" --arg name "$asset_name" \
            '.id==$id and .name==$name' "$response_file" >/dev/null || {
                echo "Asset identity mismatch; preserving it" >&2; exit 1;
            }
        status="$(delete_status "$admin_jar" "/api/assets/$asset_id")"
        [[ "$status" == 204 || "$status" == 200 ]] || {
            echo "Asset cleanup failed (HTTP $status)" >&2; exit 1;
        }
    elif [[ "$status" != 404 ]]; then
        echo "Asset lookup failed (HTTP $status)" >&2; exit 1
    fi
fi

if [[ "$user_id" =~ ^[0-9]+$ ]]; then
    status="$(get_status "$admin_jar" "/api/users/$user_id")"
    if [[ "$status" == 200 ]]; then
        jq -e --argjson id "$user_id" --arg name "$user_name" --arg email "$user_email" \
            '.id==$id and .username==$name and .email==$email' "$response_file" >/dev/null || {
                echo "User identity mismatch; preserving it" >&2; exit 1;
            }
        status="$(delete_status "$admin_jar" "/api/users/$user_id")"
        [[ "$status" == 204 || "$status" == 200 ]] || {
            echo "User cleanup failed (HTTP $status)" >&2; exit 1;
        }
    elif [[ "$status" != 404 ]]; then
        echo "User lookup failed (HTTP $status)" >&2; exit 1
    fi
fi

temp="$(mktemp "$MANIFEST.XXXXXX")"
jq --arg cleaned "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '.cleanedAt=$cleaned' "$MANIFEST" > "$temp"
mv "$temp" "$MANIFEST"
echo "Manual fixture cleanup completed: $MANIFEST"
