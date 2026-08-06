#!/bin/bash
# =============================================================================
# Secman Docker — shared secret-generation helper
# =============================================================================
# Sourced by docker/compose-up.sh and docker/compose-up-split.sh — not meant
# to be run directly.
#
#   source "$SCRIPT_DIR/generate-env.sh"
#   secman_ensure_env_file "$ENV_FILE" "$ENV_EXAMPLE" "$MODE"
#
# Creates $ENV_FILE from $ENV_EXAMPLE (if missing) with freshly generated
# secrets (JWT, encryption password/salt, DB passwords). No-op if $ENV_FILE
# already exists — both callers share this one file, so a secret generated
# for one flow is reused by the other instead of being regenerated.
# =============================================================================

# A portable random secret generator: openssl if present, else /dev/urandom.
secman_gen() {
  local kind="$1"
  if command -v openssl >/dev/null 2>&1; then
    case "$kind" in
      b64_32) openssl rand -base64 32 ;;
      hex_32) openssl rand -hex 32 ;;
      hex_8)  openssl rand -hex 8 ;;
      hex_12) openssl rand -hex 12 ;;
    esac
  else
    case "$kind" in
      b64_32) head -c 32 /dev/urandom | base64 ;;
      hex_32) head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' ;;
      hex_8)  head -c 8  /dev/urandom | od -An -tx1 | tr -d ' \n' ;;
      hex_12) head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n' ;;
    esac
  fi
}

# sed -i portability (GNU vs BSD/macOS)
secman_sedi() {
  if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi
}

secman_ensure_env_file() {
  local env_file="$1" env_example="$2" mode="${3:-}"

  if [[ -f "$env_file" ]]; then
    return 0
  fi

  echo "[generate-env] $env_file not found — creating it from $(basename "$env_example") with fresh secrets."
  cp "$env_example" "$env_file"

  local jwt enc_pw enc_salt db_pw db_root_pw
  jwt="$(secman_gen b64_32)"
  enc_pw="$(secman_gen hex_32)"
  enc_salt="$(secman_gen hex_8)"
  db_pw="$(secman_gen hex_12)"
  db_root_pw="$(secman_gen hex_12)"

  set_kv() { secman_sedi "s|^$1=.*|$1=$2|" "$env_file"; }

  set_kv JWT_SECRET "$jwt"
  set_kv SECMAN_ENCRYPTION_PASSWORD "$enc_pw"
  set_kv SECMAN_ENCRYPTION_SALT "$enc_salt"
  set_kv DB_PASSWORD "$db_pw"
  set_kv DB_ROOT_PASSWORD "$db_root_pw"

  echo "[generate-env] Wrote $env_file. Review it (URLs, DB_CONNECT, SMTP, etc.) before production use."
  if [[ "$mode" == "rds" ]]; then
    echo "[generate-env] RDS mode: edit DB_CONNECT/DB_USERNAME/DB_PASSWORD in $env_file to match your RDS instance."
  fi
}
