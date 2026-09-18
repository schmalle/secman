#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"

usage() {
    cat <<'EOF'
Usage: ./scripts/download-user-mappings-s3.sh OUTPUT [--force] [--quiet]

Download the raw AWS user-mapping file from the S3 bucket and object key
stored in Proton Pass. The file is not imported into SecMan.

Options:
  -f, --force  Overwrite OUTPUT if it already exists
  -q, --quiet  Suppress progress output
  -h, --help   Show this help
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

if [[ $# -lt 1 ]]; then
    usage >&2
    exit 2
fi

OUTPUT_PATH="$1"
shift

for option in "$@"; do
    case "$option" in
        --force|-f|--quiet|-q) ;;
        *)
            echo "ERROR: unsupported option: $option" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ ! -f "$JAR_PATH" ]]; then
    echo "ERROR: CLI JAR not found at $JAR_PATH" >&2
    echo "Build it first with: ./gradlew :cli:shadowJar" >&2
    exit 1
fi

if ! command -v pass-cli >/dev/null 2>&1; then
    echo "ERROR: pass-cli is not installed or not in PATH" >&2
    exit 1
fi

export AWS_ACCESS_KEY_ID="pass://Test/SECMAN/SECMAN_AWS_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="pass://Test/SECMAN/SECMAN_AWS_SECRET_ACCESS_KEY"
export AWS_SESSION_TOKEN="pass://Test/SECMAN/SECMAN_AWS_ACCESS_TOKEN"
export AWS_REGION="pass://Test/SECMAN/SECMAN_AWS_REGION"
export AWS_ACCOUNT_BUCKET_NAME="pass://Test/SECMAN/AWS_ACCOUNT_BUCKET_NAME"
export AWS_ACCOUNT_BUCKET_NAME="cov-sec-c7n-accounts"
export AWS_ACCOUNT_BUCKET_KEY_NAME="pass://Test/SECMAN/AWS_ACCOUNT_BUCKET_KEY_NAME"

# Expand the bucket and key only inside the shell launched by pass-cli. Expanding
# them earlier would pass the unresolved pass:// references to the CLI.
exec pass-cli run -- bash -c '
    exec java -Xmx512m -jar "$1" manage-user-mappings download-s3 \
        --bucket "$AWS_ACCOUNT_BUCKET_NAME" \
        --key "$AWS_ACCOUNT_BUCKET_KEY_NAME" \
        --output "$2" "${@:3}"
' bash "$JAR_PATH" "$OUTPUT_PATH" "$@"
