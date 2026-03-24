#!/usr/bin/env bash
# on-file-changed.sh — PostToolUse hook that flags when a backend restart is needed.
#
# Called by Claude Code after every Edit/Write operation.
# Writes a marker file if a Kotlin/Java/config file was changed, so the
# e2e-runner skill knows the backend needs restarting before the next test run.
#
# Usage (configured in .claude/settings.json):
#   "command": ".claude/skills/e2e-runner/scripts/on-file-changed.sh $TOOL_INPUT"

set -euo pipefail

# The tool input comes as JSON on stdin; extract the file path.
# We use a simple grep approach to avoid requiring jq.
FILE_PATH=""
if [ -t 0 ]; then
  # No stdin — try positional arg
  FILE_PATH="${1:-}"
else
  FILE_PATH=$(cat | grep -oP '"file_path"\s*:\s*"\K[^"]+' 2>/dev/null || echo "")
fi

if [ -z "$FILE_PATH" ]; then
  exit 0
fi

MARKER_DIR=".e2e-logs"
mkdir -p "$MARKER_DIR"

# Check if the changed file is a backend file
if [[ "$FILE_PATH" =~ \.(kt|java|scala|conf|yml|yaml)$ ]] || [[ "$FILE_PATH" =~ build\.gradle ]]; then
  echo "backend" >> "${MARKER_DIR}/restart-needed"
  echo "⚠️  Backend file changed: ${FILE_PATH} — restart needed before next test run" >&2
fi

# Check if frontend config changed (needs full restart, not hot-reload)
if [[ "$FILE_PATH" =~ astro\.config ]] || [[ "$FILE_PATH" =~ vite\.config ]]; then
  echo "frontend" >> "${MARKER_DIR}/restart-needed"
  echo "⚠️  Frontend config changed: ${FILE_PATH} — restart needed" >&2
fi

exit 0
