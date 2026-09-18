#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$TMP_DIR/repo/scripts" "$TMP_DIR/repo/src/cli/build/libs" "$TMP_DIR/bin"
cp "$REPO_ROOT/scripts/download-user-mappings-s3.sh" "$TMP_DIR/repo/scripts/"
touch "$TMP_DIR/repo/src/cli/build/libs/cli-0.1.0-all.jar"

cat > "$TMP_DIR/bin/pass-cli" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$AWS_ACCESS_KEY_ID" > "${CAPTURED_PASS_REFERENCE:?}"

[[ "$1" == "run" && "$2" == "--" ]]
shift 2

export AWS_ACCESS_KEY_ID="resolved-access-key"
export AWS_SECRET_ACCESS_KEY="resolved-secret-key"
export AWS_SESSION_TOKEN="resolved-session-token"
export AWS_REGION="eu-central-1"
export AWS_ACCOUNT_BUCKET_NAME="mapping-bucket"
export AWS_ACCOUNT_BUCKET_KEY_NAME="exports/user-mappings.csv"

exec "$@"
STUB

cat > "$TMP_DIR/bin/java" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${CAPTURED_JAVA_ARGS:?}"
printf '%s\n' "$AWS_ACCESS_KEY_ID" > "${CAPTURED_RESOLVED_ACCESS_KEY:?}"
STUB

chmod +x "$TMP_DIR/bin/pass-cli" "$TMP_DIR/bin/java"

export CAPTURED_PASS_REFERENCE="$TMP_DIR/pass-reference.txt"
export CAPTURED_JAVA_ARGS="$TMP_DIR/java-args.txt"
export CAPTURED_RESOLVED_ACCESS_KEY="$TMP_DIR/resolved-access-key.txt"

PATH="$TMP_DIR/bin:$PATH" \
    "$TMP_DIR/repo/scripts/download-user-mappings-s3.sh" \
    "$TMP_DIR/aws-user-mappings.csv" --force --quiet

assert_argument_pair() {
    local expected_flag="$1"
    local expected_value="$2"

    if ! awk -v flag="$expected_flag" -v value="$expected_value" \
        'previous == flag && $0 == value { found = 1 } { previous = $0 } END { exit !found }' \
        "$CAPTURED_JAVA_ARGS"; then
        echo "Expected $expected_flag $expected_value in Java arguments" >&2
        exit 1
    fi
}

assert_argument_pair --bucket mapping-bucket
assert_argument_pair --key exports/user-mappings.csv
assert_argument_pair --output "$TMP_DIR/aws-user-mappings.csv"

grep -Fx -- "--force" "$CAPTURED_JAVA_ARGS" >/dev/null
grep -Fx -- "--quiet" "$CAPTURED_JAVA_ARGS" >/dev/null

if [[ "$(cat "$CAPTURED_PASS_REFERENCE")" != "pass://Test/SECMAN/SECMAN_AWS_ACCESS_KEY_ID" ]]; then
    echo "Expected the AWS access key to be sourced from Proton Pass" >&2
    exit 1
fi

if [[ "$(cat "$CAPTURED_RESOLVED_ACCESS_KEY")" != "resolved-access-key" ]]; then
    echo "Expected pass-cli to resolve credentials before launching the CLI" >&2
    exit 1
fi
