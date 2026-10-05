#!/usr/bin/env bash
# Verify argv preservation, working-directory independence, and exit propagation.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/scripts" "$TEST_DIR/bin"
cp "$REPO_ROOT/scripts/user-access-overview-macos.sh" "$REPO_ROOT/scripts/user-access-overview-aws.sh" "$TEST_DIR/scripts/"
for tool in java pass-cli; do
    printf '#!/usr/bin/env bash\nexit 0\n' > "$TEST_DIR/bin/$tool"
    chmod +x "$TEST_DIR/bin/$tool"
done
for wrapper in secman secmancliaws.sh; do
    cat > "$TEST_DIR/scripts/$wrapper" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$TEST_ARGV"
exit 37
STUB
    chmod +x "$TEST_DIR/scripts/$wrapper"
done
export PATH="$TEST_DIR/bin:$PATH" TEST_ARGV="$TEST_DIR/argv"
for platform in macos aws; do
    set +e
    (cd / && "$TEST_DIR/scripts/user-access-overview-$platform.sh" --email 'user with spaces@example.com' --format json)
    status=$?
    set -e
    [[ "$status" == 37 ]] || { echo "Failed: $platform exit propagation" >&2; exit 1; }
    printf '%s\n' user-access-overview --email 'user with spaces@example.com' --format json > "$TEST_DIR/expected"
    cmp "$TEST_DIR/expected" "$TEST_ARGV"
done
for scenario in missing-aws missing-jq fetch-error empty-secret; do
    set +e
    SDKMAN_DIR="$TEST_DIR/no-sdkman" NVM_DIR="$TEST_DIR/no-nvm" /bin/bash -c '
        source "$1"
        scenario="$2"
        aws() { if [[ "$scenario" == empty-secret ]]; then printf None; else return 9; fi; }
        jq() { return 0; }
        command() {
            if [[ "$1" == -v && (("$scenario" == missing-aws && "$2" == aws) || ("$scenario" == missing-jq && "$2" == jq)) ]]; then
                return 1
            fi
            builtin command "$@"
        }
        secman_aws_load_secret
    ' bash "$REPO_ROOT/scripts/lib/aws-secrets.sh" "$scenario" > "$TEST_DIR/stdout" 2> "$TEST_DIR/stderr"
    status=$?
    set -e
    [[ "$status" != 0 && ! -s "$TEST_DIR/stdout" && -s "$TEST_DIR/stderr" ]] || {
        echo "Failed: AWS $scenario diagnostics must use stderr" >&2; exit 1;
    }
done
echo "Access overview wrappers: 2 passed; AWS failure diagnostics: 4 passed"
