#!/bin/bash
# Run the backend (backendng) test suite against an EXTERNAL MariaDB — no Docker/Testcontainers.
#
# The integration tests (BaseIntegrationTest subclasses) connect to a dedicated, disposable test
# database. The schema is created and DROPPED on every run via Hibernate hbm2ddl=create-drop, so
# TEST_DB_URL must point at a throwaway test DB — NEVER the dev/prod database (DB_CONNECT).
#
# Defaults target a local `secman_test` database. Override any of TEST_DB_URL / TEST_DB_USERNAME /
# TEST_DB_PASSWORD via the environment (e.g. exported by pass-cli) to point elsewhere.
#
# One-time local setup (run as a DB admin):
#   CREATE DATABASE IF NOT EXISTS secman_test;
#   CREATE USER IF NOT EXISTS 'secman_test'@'localhost' IDENTIFIED BY 'secman_test';
#   GRANT ALL PRIVILEGES ON secman_test.* TO 'secman_test'@'localhost';
#
# Usage:
#   ./scripts/runbackendtests.sh                                  # whole suite
#   ./scripts/runbackendtests.sh --tests "*VulnerabilityService*" # filter (any gradle test args)
#   TEST_DB_URL=jdbc:mariadb://db:3306/secman_test ./scripts/runbackendtests.sh

set -euo pipefail

# Repo root = parent of this script's directory, so the script works from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Datasource for @MicronautTest(environments=["test"]); consumed by application-test.yml.
# `:=` keeps any value already exported in the environment (e.g. via pass-cli).
: "${TEST_DB_URL:=jdbc:mariadb://127.0.0.1:3306/secman_test}"
: "${TEST_DB_USERNAME:=secman_test}"
: "${TEST_DB_PASSWORD:=secman_test}"
export TEST_DB_URL TEST_DB_USERNAME TEST_DB_PASSWORD

echo "Running backendng tests against ${TEST_DB_URL} (user: ${TEST_DB_USERNAME})"
exec ./gradlew :backendng:test "$@"
