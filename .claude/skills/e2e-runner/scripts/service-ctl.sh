#!/usr/bin/env bash
# service-ctl.sh — Start / stop / restart a background service with logging.
#
# Usage:
#   ./scripts/service-ctl.sh start <name> <log_dir> <command...>
#   ./scripts/service-ctl.sh stop  <name> <log_dir>
#   ./scripts/service-ctl.sh restart <name> <log_dir> <command...>
#   ./scripts/service-ctl.sh status <name> <log_dir>
#
# PID is stored in <log_dir>/<name>.pid
# Logs go to <log_dir>/<name>.log

set -euo pipefail

ACTION="${1:?Usage: service-ctl.sh <start|stop|restart|status> <name> <log_dir> [command...]}"
NAME="${2:?Missing service name}"
LOG_DIR="${3:?Missing log directory}"
shift 3

PID_FILE="${LOG_DIR}/${NAME}.pid"
LOG_FILE="${LOG_DIR}/${NAME}.log"

mkdir -p "$LOG_DIR"

_is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

_start() {
  if _is_running; then
    echo "⚠️  ${NAME} is already running (PID $(cat "$PID_FILE"))"
    return 0
  fi

  echo "🚀 Starting ${NAME}..."
  echo "   Command: $*"
  echo "   Log:     ${LOG_FILE}"

  # Start in background, capture PID
  nohup "$@" > "$LOG_FILE" 2>&1 &
  local PID=$!
  echo "$PID" > "$PID_FILE"
  echo "   PID:     ${PID}"
}

_stop() {
  if ! _is_running; then
    echo "ℹ️  ${NAME} is not running"
    rm -f "$PID_FILE"
    return 0
  fi

  local PID
  PID=$(cat "$PID_FILE")
  echo "🛑 Stopping ${NAME} (PID ${PID})..."

  # Graceful shutdown first, then force after 10s
  kill "$PID" 2>/dev/null || true
  local WAIT=0
  while kill -0 "$PID" 2>/dev/null && [ "$WAIT" -lt 10 ]; do
    sleep 1
    WAIT=$((WAIT + 1))
  done

  if kill -0 "$PID" 2>/dev/null; then
    echo "   Force-killing ${NAME}..."
    kill -9 "$PID" 2>/dev/null || true
  fi

  rm -f "$PID_FILE"
  echo "   ${NAME} stopped."
}

_status() {
  if _is_running; then
    echo "✅ ${NAME} is running (PID $(cat "$PID_FILE"))"
  else
    echo "⭕ ${NAME} is not running"
  fi
}

case "$ACTION" in
  start)
    _start "$@"
    ;;
  stop)
    _stop
    ;;
  restart)
    _stop
    _start "$@"
    ;;
  status)
    _status
    ;;
  *)
    echo "Unknown action: ${ACTION}"
    exit 1
    ;;
esac
