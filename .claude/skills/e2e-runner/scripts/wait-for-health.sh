#!/usr/bin/env bash
# wait-for-health.sh — Poll a URL until it returns HTTP 2xx or timeout.
#
# Usage:
#   ./scripts/wait-for-health.sh <url> [timeout_seconds]
#
# Exit codes:
#   0  — service is healthy
#   1  — timed out

set -euo pipefail

URL="${1:?Usage: wait-for-health.sh <url> [timeout_seconds]}"
TIMEOUT="${2:-120}"
INTERVAL=2
ELAPSED=0

echo "⏳ Waiting for ${URL} (timeout: ${TIMEOUT}s)..."

while [ "$ELAPSED" -lt "$TIMEOUT" ]; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "$URL" 2>/dev/null || echo "000")
  if [[ "$STATUS" =~ ^2[0-9]{2}$ ]]; then
    echo "✅ ${URL} is healthy (HTTP ${STATUS}) after ${ELAPSED}s"
    exit 0
  fi
  sleep "$INTERVAL"
  ELAPSED=$((ELAPSED + INTERVAL))
done

echo "❌ ${URL} did not become healthy within ${TIMEOUT}s (last status: ${STATUS})"
exit 1
