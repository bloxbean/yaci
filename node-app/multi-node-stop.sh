#!/usr/bin/env bash
#
# Stop all running yaci-node processes.
#
set -euo pipefail

PIDS=$(pgrep -f 'yaci-node\.jar' || true)

if [ -z "$PIDS" ]; then
  echo "No yaci-node processes found."
  exit 0
fi

echo "Stopping yaci-node processes: $PIDS"
kill $PIDS 2>/dev/null || true

# Wait briefly for graceful shutdown
sleep 2

# Force kill if still running
REMAINING=$(pgrep -f 'yaci-node\.jar' || true)
if [ -n "$REMAINING" ]; then
  echo "Force killing remaining processes: $REMAINING"
  kill -9 $REMAINING 2>/dev/null || true
fi

echo "All yaci-node processes stopped."
