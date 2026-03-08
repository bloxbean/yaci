#!/bin/bash
# Start Yaci node-app in devnet mode with Quarkus hot-reload (dev mode).
# Code changes are picked up automatically without restart.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

exec ../gradlew :node-app:quarkusDev -Dquarkus.profile=devnet "$@"
