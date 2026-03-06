#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ "$1" = "--native" ]; then
  shift
  BINARY="build/yaci-node"
  [ ! -f "$BINARY" ] && echo "Native binary not found. Build: ./gradlew :node-app:build -Dquarkus.native.enabled=true" && exit 1
  exec "$BINARY" -Dquarkus.profile=devnet "$@"
else
  JAR="build/quarkus-app/quarkus-run.jar"
  [ ! -f "$JAR" ] && echo "Jar not found. Build: ./gradlew :node-app:quarkusBuild" && exit 1
  exec java -Dquarkus.profile=devnet -jar "$JAR" "$@"
fi
