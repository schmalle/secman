#!/bin/bash

export DB_CONNECT="jdbc:mariadb://127.0.0.1:3306/secman?useSsl=true"
export AWS_REGION="pass://Test/SECMAN/SECMAN_AWS_REGION"
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

# Compare the complete AWS export against stored inventory. The 72-hour display
# window must not shrink this reconciliation population or its deletion safety brake.
pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- \
    java -Xmx4g -Xms2g -jar "$JAR_PATH" asset-match-clear \
    --bucket covestro-ec2-export \
    --key cov-instances.json \
    --strict \
    --max-delete-percent 50
