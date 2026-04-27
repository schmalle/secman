#!/bin/bash
# Stop dev-mode frontend (npm run dev / Vite / Astro) by killing whatever is bound to port 4321.
# Companion to ./scriptpp/startfrontenddev.sh — never targets the systemd service.

set -u
PORT=4321
PIDS=$(lsof -ti :"$PORT" 2>/dev/null || true)

if [ -z "$PIDS" ]; then
  echo "No frontend dev process listening on port $PORT."
  exit 0
fi

echo "Stopping frontend dev process(es) on port $PORT: $PIDS"
kill $PIDS 2>/dev/null || true

for _ in 1 2 3 4 5; do
  sleep 1
  REMAINING=$(lsof -ti :"$PORT" 2>/dev/null || true)
  [ -z "$REMAINING" ] && break
done

REMAINING=$(lsof -ti :"$PORT" 2>/dev/null || true)
if [ -n "$REMAINING" ]; then
  echo "Force-killing remaining process(es) on port $PORT: $REMAINING"
  kill -9 $REMAINING 2>/dev/null || true
fi
