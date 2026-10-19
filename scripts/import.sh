#!/bin/bash

export DB_CONNECT="${DB_CONNECT:-jdbc:mariadb://127.0.0.1:3306/secman?useSsl=true}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "Error: CLI JAR not found at $JAR_PATH"
    echo ""
    echo "Build it first with:"
    echo "  ./gradlew :cli:shadowJar"
    exit 1
fi

# Resolve vault settings before computing the discovery window. The isolated runner's
# database is restored below because the vault may also contain normal-stack DB settings.
if [[ "${SECMAN_IMPORT_ENV_READY:-}" != "1" ]]; then
    exec pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- env SECMAN_IMPORT_ENV_READY=1 bash "$0" "$@"
fi

RETENTION_DAYS="${CROWDSTRIKE_SENSOR_RETENTION_DAYS:-0}"
LAST_SEEN_DAYS="${CROWDSTRIKE_IMPORT_LAST_SEEN_DAYS:-3}"
for value in "$RETENTION_DAYS" "$LAST_SEEN_DAYS"; do
    if [[ ! "$value" =~ ^[0-9]{1,5}$ ]] || (( 10#$value > 36500 )); then
        echo "Error: CrowdStrike contact windows must be integers between 0 and 36500" >&2
        exit 1
    fi
done
if (( 10#$LAST_SEEN_DAYS == 0 || 10#$LAST_SEEN_DAYS > 3 )); then
    LAST_SEEN_DAYS=3
fi
if (( 10#$RETENTION_DAYS > 0 && 10#$RETENTION_DAYS < 10#$LAST_SEEN_DAYS )); then
    LAST_SEEN_DAYS="$RETENTION_DAYS"
fi
if [[ -n "${SECMAN_TEST_ISOLATED_DB:-}" ]]; then
    export DB_NAME="$SECMAN_TEST_ISOLATED_DB"
    export DB_CONNECT="$SECMAN_TEST_DB_CONNECT"
    export DB_USERNAME="$SECMAN_TEST_DB_USERNAME" DB_USER="$SECMAN_TEST_DB_USERNAME"
    export DB_PASSWORD="$SECMAN_TEST_DB_PASSWORD" DB_PASS="$SECMAN_TEST_DB_PASSWORD"
    export SECMAN_BACKEND_URL="$SECMAN_E2E_BACKEND_URL"
    BASE_URL="$SECMAN_E2E_BACKEND_URL"
    # shellcheck source=test/lib/isolated-target.sh
    source "$REPO_ROOT/scripts/test/lib/isolated-target.sh"
    secman_test_require_isolated || exit 2
fi
exec java -Xmx4g -Xms2g -jar "$JAR_PATH" query servers --device-type SERVER_FAMILY --severity CRITICAL,HIGH --min-days-open 1 --save --last-seen-days "$LAST_SEEN_DAYS"
