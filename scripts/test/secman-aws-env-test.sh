#!/usr/bin/env bash
# Verify vault mappings and wrapper execution without AWS, Java or a backend.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
fixture="$tmp_dir/repo with spaces"
mkdir -p "$fixture/scripts" "$fixture/src/cli/build/libs" "$tmp_dir/bin"
cp -P "$repo_root/scripts/secman" "$repo_root/scripts/secmancli" "$fixture/scripts/"
touch "$fixture/src/cli/build/libs/cli-0.1.0-all.jar"
# Copy only AWS vault references; unrelated local secrets never enter the fixture.
awk '/^AWS_/ { print }' "$repo_root/secmanpp.env" > "$fixture/secmanpp.env"

while read -r name field; do
    if ! grep -Fxq "$name=pass://Test/SECMAN/$field" "$fixture/secmanpp.env"; then
        echo "Missing or incorrect vault mapping for $name" >&2
        exit 1
    fi
done <<'MAPPINGS'
AWS_ACCESS_KEY_ID SECMAN_AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY SECMAN_AWS_SECRET_ACCESS_KEY
AWS_SESSION_TOKEN SECMAN_AWS_ACCESS_TOKEN
AWS_REGION SECMAN_AWS_REGION
AWS_ASSET_BUCKET_NAME AWS_ASSET_BUCKET_NAME
AWS_ASSET_BUCKET_KEY_NAME AWS_ASSET_BUCKET_KEY_NAME
MAPPINGS

cat > "$tmp_dir/bin/pass-cli" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == run && "$2" == --env-file && "$3" == "$EXPECTED_ROOT/secmanpp.env" && "$4" == -- ]]
while IFS='=' read -r name reference; do
    [[ "$reference" == pass://Test/SECMAN/* ]]
    export "$name=resolved-$name"
done < "$3"
shift 4
exec "$@"
STUB

cat > "$tmp_dir/bin/java" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
for name in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_REGION AWS_ASSET_BUCKET_NAME AWS_ASSET_BUCKET_KEY_NAME; do
    [[ "${!name:-}" == "resolved-$name" ]] || { echo "Unresolved $name" >&2; exit 1; }
done
[[ "$1" == -Xmx512m && "$2" == -jar && "$3" == "$EXPECTED_ROOT/src/cli/build/libs/cli-0.1.0-all.jar" ]]
shift 3
[[ $# == 6 && "$1" == asset-match-clear && "$2" == --dry-run && "$3" == --key && "$4" == 'snapshot with spaces.json' && "$5" == --aws-region && "$6" == eu-west-1 ]]
STUB
chmod +x "$tmp_dir/bin/pass-cli" "$tmp_dir/bin/java"

# Run from outside the checkout to verify absolute env/JAR paths as well.
for wrapper in secman secmancli; do
    (
        cd "$tmp_dir"
        PATH="$tmp_dir/bin:$PATH" EXPECTED_ROOT="$fixture" \
            bash "$fixture/scripts/$wrapper" asset-match-clear --dry-run \
            --key 'snapshot with spaces.json' --aws-region eu-west-1
    )
done
echo 'secman-aws-env-test: 6 vault mappings and both wrappers passed'
