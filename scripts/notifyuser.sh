#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage:
  scripts/notifyuser.sh [USER_EMAIL] [--dry-run] [--days DAYS] [--skip-import]
  scripts/notifyuser.sh --notification-user USER_EMAIL --dry-run

Options:
  --notification-user, --user EMAIL  Notify only this user. A positional email is also accepted.
  --dry-run                          Preview notifications without sending email.
  --days DAYS                        Vulnerability age threshold in days. Default: 30.
  --help, -h                         Show this help.

Examples:
  scripts/notifyuser.sh --dry-run
  scripts/notifyuser.sh user@example.com --dry-run
  scripts/notifyuser.sh --notification-user user@example.com --days 60
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"

NOTIFICATION_USER=""
DRY_RUN=false
DAYS=30
SKIP_IMPORT=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --notification-user|--user)
            if [[ $# -lt 2 || "$2" == --* ]]; then
                echo "Error: $1 requires an email address" >&2
                exit 1
            fi
            NOTIFICATION_USER="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --days)
            if [[ $# -lt 2 || "$2" == --* ]]; then
                echo "Error: --days requires a numeric value" >&2
                exit 1
            fi
            DAYS="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        --*)
            echo "Error: Unknown option: $1" >&2
            usage
            exit 1
            ;;
        *)
            if [[ -n "$NOTIFICATION_USER" ]]; then
                echo "Error: notification user specified more than once" >&2
                exit 1
            fi
            NOTIFICATION_USER="$1"
            shift
            ;;
    esac
done

if ! [[ "$DAYS" =~ ^[0-9]+$ ]] || [[ "$DAYS" -lt 1 ]]; then
    echo "Error: --days must be a positive integer" >&2
    exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
    echo "Error: CLI JAR not found at $JAR_PATH"
    echo
    echo "Build it first with:"
    echo "  ./gradlew :cli:shadowJar"
    exit 1
fi

export DB_CONNECT="${DB_CONNECT:-jdbc:mariadb://127.0.0.1:3306/secman?useSsl=true}"

run_cli() {
    pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- \
        java -Xmx4g -Xms2g -jar "$JAR_PATH" "$@"
}

notify_args=(send-notification-users --days "$DAYS")

if [[ "$DRY_RUN" == true ]]; then
    notify_args+=(--dry-run)
fi

if [[ -n "$NOTIFICATION_USER" ]]; then
    notify_args+=(--notification-user "$NOTIFICATION_USER")
fi

echo "Sending exception-aware user vulnerability notifications..."
run_cli "${notify_args[@]}"
