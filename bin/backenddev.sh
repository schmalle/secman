#!/bin/bash
# secman - CLI wrapper for secman security management tool

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_BACKEND_URL="op://test/secman/SECMAN_BACKEND_BASE_URL"
export DB_CONNECT="op://test/secman/DB_CONNECT"

export JWT_SECRET=$(openssl rand -base64 48)

op run -- gradle :backendng:clean backendng:run
