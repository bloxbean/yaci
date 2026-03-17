#!/usr/bin/env bash
#
# Yaci Node start script
# Auto-detects JAR vs native mode and supports Quarkus profiles.
#
# Usage:
#   ./yaci-node.sh                      # Default (preprod relay)
#   ./yaci-node.sh --devnet             # Local devnet with block production
#   ./yaci-node.sh --mainnet            # Mainnet relay
#   ./yaci-node.sh --preview            # Preview relay
#   ./yaci-node.sh --profile=<name>     # Custom Quarkus profile
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Parse profile from arguments
PROFILE=""
PASSTHROUGH_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --devnet)
            PROFILE="devnet"
            ;;
        --mainnet)
            PROFILE="mainnet"
            ;;
        --preview)
            PROFILE="preview"
            ;;
        --profile=*)
            PROFILE="${arg#--profile=}"
            ;;
        *)
            PASSTHROUGH_ARGS+=("$arg")
            ;;
    esac
done

# Build profile system property if set
PROFILE_PROP=""
if [ -n "$PROFILE" ]; then
    PROFILE_PROP="-Dquarkus.profile=${PROFILE}"
fi

# Auto-detect mode: native binary or JAR
if [ -f "$SCRIPT_DIR/yaci-node" ]; then
    # Native binary mode
    echo "Starting Yaci Node (native)${PROFILE:+ with profile: $PROFILE}..."
    exec "$SCRIPT_DIR/yaci-node" \
        -Dyaci.node.block-producer.script-evaluator=scalus \
        $PROFILE_PROP "${PASSTHROUGH_ARGS[@]}"
elif [ -f "$SCRIPT_DIR/yaci-node.jar" ]; then
    # Uber-jar mode
    echo "Starting Yaci Node (JVM)${PROFILE:+ with profile: $PROFILE}..."
    exec java ${JAVA_OPTS:-} $PROFILE_PROP -jar "$SCRIPT_DIR/yaci-node.jar" "${PASSTHROUGH_ARGS[@]}"
else
    echo "Error: Neither 'yaci-node' binary nor 'yaci-node.jar' found in $SCRIPT_DIR"
    echo "Please ensure the distribution is complete."
    exit 1
fi
