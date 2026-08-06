#!/usr/bin/env bash
# Cron watchdog: restarts the backend if it fails a health check.
set -euo pipefail

HEALTH_URL="http://localhost:8080/health"
RESTART_SCRIPT="/opt/secman/app/scripts/restartbackend.sh"

response="$(curl -s --max-time 5 -w '\n%{http_code}' "$HEALTH_URL" || true)"
http_code="$(echo "$response" | tail -n1)"
body="$(echo "$response" | sed '$d')"

if [[ "$http_code" == "200" ]] && ! echo "$body" | grep -q '"status"[[:space:]]*:[[:space:]]*"DOWN"'; then
    exit 0
fi

echo "$(date -Is) backend health check failed (HTTP ${http_code:-none}, body: ${body:-empty}); restarting via ${RESTART_SCRIPT}"
exec "$RESTART_SCRIPT"
