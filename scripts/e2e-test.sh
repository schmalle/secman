#!/bin/bash
# secman - CLI wrapper for secman security management tool

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_BACKEND_URL="op://test/secman/SECMAN_BACKEND_BASE_URL"
export DB_CONNECT="op://test/secman/DB_CONNECT"
export SECMAN_DEBUG=true

export FALCON_CLIENT_ID="op://test/secman/FALCON_CLIENT_ID"
export FALCON_CLIENT_SECRET="op://test/secman/FALCON_CLIENT_SECRET"
export FALCON_CLOUD_REGION="op://test/secman/FALCON_CLOUD_REGION"
export SECMAN_OPENROUTER_API_KEY="op://test/secman/OPENROUTER_API_KEY"
export SECMAN_ADMIN_NAME="op://test/secman/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="op://test/secman/SECMAN_ADMIN_PASS"
export API_KEY="op://test/secman/MCP_API_KEY"
export SECMAN_ADMIN_EMAIL="op://test/secman/SECMAN_ADMIN_EMAIL"
export AWS_ACCESS_KEY_ID="op://test/secman/SECMAN_AWS_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="op://test/secman/SECMAN_AWS_SECRET_ACCESS_KEY"
export AWS_SESSION_TOKEN="op://test/secman/SECMAN_AWS_ACCESS_TOKEN"
export SECMAN_BACKEND_URL="op://test/secman/SECMAN_BACKEND_BASE_URL"
export SECMAN_INSECURE="op://test/secman/SECMAN_SSL_ACCEPT_ALL"



export JWT_SECRET=$(openssl rand -base64 48)

op run -- ./scripts/e2e-test.2.sh
