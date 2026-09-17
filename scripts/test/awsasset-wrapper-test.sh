#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/repo/scripts" "$tmp_dir/repo/src/cli/build/libs" "$tmp_dir/bin"
cp "$repo_root/scripts/awsasset.sh" "$tmp_dir/repo/scripts/awsasset.sh"
touch "$tmp_dir/repo/src/cli/build/libs/cli-0.1.0-all.jar"

cat > "$tmp_dir/bin/pass-cli" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${CAPTURED_PASS_CLI_ARGS:?}"
printf '%s\n' "${AWS_REGION:-}" > "${CAPTURED_AWS_REGION:?}"
STUB
chmod +x "$tmp_dir/bin/pass-cli"

export CAPTURED_PASS_CLI_ARGS="$tmp_dir/pass-cli-args.txt"
export CAPTURED_AWS_REGION="$tmp_dir/aws-region.txt"
PATH="$tmp_dir/bin:$PATH" "$tmp_dir/repo/scripts/awsasset.sh"

assert_argument_pair() {
    local expected_flag="$1"
    local expected_value="$2"

    if ! awk -v flag="$expected_flag" -v value="$expected_value" \
        'previous == flag && $0 == value { found = 1 } { previous = $0 } END { exit !found }' \
        "$CAPTURED_PASS_CLI_ARGS"; then
        echo "Expected $expected_flag $expected_value in pass-cli arguments" >&2
        sed 's/^/Actual argument: /' "$CAPTURED_PASS_CLI_ARGS" >&2
        exit 1
    fi
}

assert_argument_pair --bucket covestro-ec2-export
assert_argument_pair --key cov-instances.json

if [[ "$(cat "$CAPTURED_AWS_REGION")" != "pass://Test/SECMAN/SECMAN_AWS_REGION" ]]; then
    echo "Expected awsasset.sh to source AWS_REGION from Proton Pass" >&2
    exit 1
fi
