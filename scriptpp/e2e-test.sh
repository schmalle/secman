#!/bin/bash
# secman - CLI wrapper for secman security management tool

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_BACKEND_URL="pass://test/secman/SECMAN_BACKEND_BASE_URL"
export DB_CONNECT="pass://test/secman/DB_CONNECT"
export SECMAN_DEBUG=true

export FALCON_CLIENT_ID="pass://test/secman/FALCON_CLIENT_ID"
export FALCON_CLIENT_SECRET="pass://test/secman/FALCON_CLIENT_SECRET"
export FALCON_CLOUD_REGION="pass://test/secman/FALCON_CLOUD_REGION"
export SECMAN_OPENROUTER_API_KEY="pass://test/secman/OPENROUTER_API_KEY"
export SECMAN_ADMIN_NAME="pass://test/secman/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="pass://test/secman/SECMAN_ADMIN_PASS"
export API_KEY="pass://test/secman/MCP_API_KEY"
export SECMAN_ADMIN_EMAIL="pass://test/secman/SECMAN_ADMIN_EMAIL"
export AWS_ACCESS_KEY_ID="pass://test/secman/SECMAN_AWS_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="pass://test/secman/SECMAN_AWS_SECRET_ACCESS_KEY"
export AWS_SESSION_TOKEN="pass://test/secman/SECMAN_AWS_ACCESS_TOKEN"
export SECMAN_BACKEND_URL="pass://test/secman/SECMAN_BACKEND_BASE_URL"
export SECMAN_INSECURE="pass://test/secman/SECMAN_SSL_ACCEPT_ALL"



export JWT_SECRET=$(openssl rand -base64 48)

pass-cli run -- ./scriptpp/e2e-test.2.sh
