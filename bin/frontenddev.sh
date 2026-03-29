#!/bin/bash
# secman - CLI wrapper for secman security management tool

cd src/frontend
export SECMAN_DOMAIN="op://test/secman/SECMAN_BACKEND_BASE_URL"
export SECMAN_HOST="op://test/secman/SECMAN_HOST"

op run -- npm run dev


