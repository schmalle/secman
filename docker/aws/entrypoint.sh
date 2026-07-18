#!/bin/bash
# Secman all-in-one container entrypoint.
# Starts the Micronaut backend, the Astro SSR server, and nginx, then exits
# as soon as ANY of the three dies — the orchestrator (ECS/Docker) restarts
# the container, which is simpler and safer than in-container resurrection.
set -u

echo "[entrypoint] starting Micronaut backend on 127.0.0.1:8080"
# shellcheck disable=SC2086 — JAVA_OPTS is intentionally word-split
java ${JAVA_OPTS:-} -jar /app/app.jar &
BACKEND_PID=$!

echo "[entrypoint] starting Astro SSR server on 127.0.0.1:4321"
# HOST/PORT are read by the @astrojs/node standalone server. Bound to
# loopback: only nginx is reachable from outside the container.
HOST=127.0.0.1 PORT=4321 node /app/frontend/server/entry.mjs &
FRONTEND_PID=$!

echo "[entrypoint] starting nginx on :80"
nginx -g 'daemon off;' &
NGINX_PID=$!

term() {
    echo "[entrypoint] caught signal, shutting down"
    kill "$NGINX_PID" "$FRONTEND_PID" "$BACKEND_PID" 2>/dev/null
    wait
    exit 0
}
trap term TERM INT

# Exit when the first child exits; propagate a failure exit code.
wait -n
STATUS=$?
echo "[entrypoint] a service exited (status $STATUS) — stopping container"
kill "$NGINX_PID" "$FRONTEND_PID" "$BACKEND_PID" 2>/dev/null
wait
exit "$STATUS"
