#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ "$1" = "--native" ]; then
  shift
  BINARY="build/yaci-node"
  [ ! -f "$BINARY" ] && echo "Native binary not found. Build: ./gradlew :node-app:build -Dquarkus.native.enabled=true" && exit 1
  exec "$BINARY" -Dquarkus.profile=devnet "$@"
else
  if [ -f "build/yaci-node.jar" ]; then
    JAR="build/yaci-node.jar"
  elif [ -f "build/quarkus-app/quarkus-run.jar" ]; then
    JAR="build/quarkus-app/quarkus-run.jar"
  else
    echo "Jar not found. Build: ./gradlew :node-app:quarkusBuild" && exit 1
  fi
  exec java -Dquarkus.profile=devnet -jar "$JAR" "$@"
fi
