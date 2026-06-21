#!/bin/bash
# secman - CLI wrapper for secman security management tool

cd src/frontend
export SECMAN_DOMAIN="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export SECMAN_HOST="pass://Test/SECMAN/SECMAN_HOST"
export FRONTEND_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export SECMAN_BACKEND_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"


pass-cli run -- npm run dev
