SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

#!/bin/bash
# secman - CLI wrapper for secman security management tool


get_secret() {
  local secret_id="$1"
  local field="${2:-}"  # optional JSON field

  local value
  value=$(aws secretsmanager get-secret-value \
    --secret-id "$secret_id" \
    --query 'SecretString' \
    --output text 2>/dev/null) || {
    echo "ERROR: Failed to retrieve secret '$secret_id'" >&2
    return 1
  }

  if [[ -n "$field" ]]; then
    echo "$value" | jq -r --arg f "$field" '.[$f]'
  else
    echo "$value"
  fi
}

# Usage
DB_PASS=


export MICRONAUT_ENVIRONMENTS=dev

export SECMAN_DEBUG=true

export SECMAN_ADMIN_NAME=$(get_secret "prod/secman/credentials" "SECMAN_ADMIN_NAME")
export SECMAN_ADMIN_PASS=$(get_secret "prod/secman/credentials" "SECMAN_ADMIN_PASS")
export SECMAN_MCP_KEY=$(get_secret "prod/secman/credentials" "SECMAN_MCP_KEY")
export SECMAN_ADMIN_EMAIL=$(get_secret "prod/secman/credentials" "SECMAN_ADMIN_EMAIL")
export SECMAN_BACKEND_URL=$(get_secret "prod/secman/credentials" "SECMAN_BACKEND_URL")
export SECMAN_INSECURE=$(get_secret "prod/secman/credentials" "SECMAN_INSECURE")
export FRONTEND_URL=$(get_secret "prod/secman/credentials" "FRONTEND_URL")
export SECMAN_BACKEND_URL=$(get_secret "prod/secman/credentials" "SECMAN_BACKEND_URL")
export AZURE_TENANT_ID=$(get_secret "prod/azure/credentials" "AZURE_TENANT_ID")
export AZURE_CLIENT_ID=$(get_secret "prod/azure/credentials" "AZURE_CLIENT_ID")
export AZURE_CLIENT_SECRET=$(get_secret "prod/azure/credentials" "AZURE_CLIENT_SECRET")

python3 ./read.py --insecure --import "$@"