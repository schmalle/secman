#!/bin/bash
# secman - CLI wrapper for secman security management tool

cd "${SECMAN_E2E_FRONTEND_DIR:-src/frontend}"
export SECMAN_DOMAIN="${SECMAN_E2E_BACKEND_URL:-pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL}"
export SECMAN_HOST="${SECMAN_E2E_FRONTEND_HOST:-pass://Test/SECMAN/SECMAN_HOST}"
export FRONTEND_URL="${SECMAN_E2E_FRONTEND_URL:-pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL}"
export SECMAN_BACKEND_URL="${SECMAN_E2E_BACKEND_URL:-pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL}"


pass-cli run -- npm run dev
