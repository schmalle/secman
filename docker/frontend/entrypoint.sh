#!/bin/bash
# Secman frontend container entrypoint.
# Starts the Astro SSR Node server and nginx, then exits as soon as EITHER
# dies — the orchestrator (docker run --restart / compose / Kubernetes)
# restarts the container rather than running with a half-broken frontend.
# Same pattern as docker/aws/entrypoint.sh, trimmed to two processes.
set -u

echo "[entrypoint] starting Astro SSR server on 127.0.0.1:4321"
# Bound to loopback: only nginx is reachable from outside the container.
HOST=127.0.0.1 PORT=4321 node /app/frontend/server/entry.mjs &
SSR_PID=$!

echo "[entrypoint] starting nginx on :8443"
nginx -g 'daemon off;' &
NGINX_PID=$!

term() {
    echo "[entrypoint] caught signal, shutting down"
    kill "$NGINX_PID" "$SSR_PID" 2>/dev/null
    wait
    exit 0
}
trap term TERM INT

# Exit when the first child exits; propagate a failure exit code.
wait -n
STATUS=$?
echo "[entrypoint] a service exited (status $STATUS) — stopping container"
kill "$NGINX_PID" "$SSR_PID" 2>/dev/null
wait
exit "$STATUS"
