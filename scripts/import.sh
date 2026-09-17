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

# --last-seen-days widened from 1 to 30 so hosts that don't check into Falcon every
# single day (stopped / intermittent EC2 instances) are still imported. A 1-day window
# silently dropped any such host before its vulns were ever queried, leaving it invisible
# to notify-user even though the live per-host lookup showed vulnerabilities. 30 aligns
# with the notification overdue SLA. SERVER_FAMILY covers Falcon's separate Server and
# Domain Controller categories without importing workstations.
pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- java -Xmx4g -Xms2g -jar ./src/cli/build/libs/cli-0.1.0-all.jar query servers --device-type SERVER_FAMILY --severity CRITICAL,HIGH --min-days-open 1 --save --last-seen-days 30
