#!/bin/bash

export DB_CONNECT="jdbc:mariadb://127.0.0.1:3306/secman?useSsl=true"
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

pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- java -Xmx4g -Xms2g -jar ./src/cli/build/libs/cli-0.1.0-all.jar query servers --device-type SERVER --severity CRITICAL,HIGH --min-days-open 1 --save --username $SECMAN_ADMIN_NAME --password $SECMAN_ADMIN_PASS --last-seen-days 1
