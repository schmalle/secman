#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/pass-cli" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" > "${CAPTURED_PASS_CLI_ARGS:?}"
exit 0
STUB
chmod +x "$tmp_dir/pass-cli"

export CAPTURED_PASS_CLI_ARGS="$tmp_dir/pass-cli-args.txt"
PATH="$tmp_dir:$PATH" "$repo_root/scripts/clearmatchasset.sh" --save --strict --check --check-fix

args="$(cat "$CAPTURED_PASS_CLI_ARGS")"
case " $args " in
  *" --save "*) ;;
  *)
    echo "Expected clearmatchasset.sh to forward --save to asset-match-clear" >&2
    echo "Actual args: $args" >&2
    exit 1
    ;;
esac

case " $args " in
  *" --strict "*) ;;
  *)
    echo "Expected clearmatchasset.sh to forward --strict to asset-match-clear" >&2
    echo "Actual args: $args" >&2
    exit 1
    ;;
esac

case " $args " in
  *" --check "*) ;;
  *)
    echo "Expected clearmatchasset.sh to forward --check to asset-match-clear" >&2
    echo "Actual args: $args" >&2
    exit 1
    ;;
esac

case " $args " in
  *" --check-fix "*) ;;
  *)
    echo "Expected clearmatchasset.sh to forward --check-fix to asset-match-clear" >&2
    echo "Actual args: $args" >&2
    exit 1
    ;;
esac
