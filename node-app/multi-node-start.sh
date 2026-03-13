#!/usr/bin/env bash
#
# Start 3 interconnected Yaci nodes for app-layer testing.
#
# Node 1: Block producer (server=13337, http=8081) — produces devnet blocks
# Node 2: Relay (server=13338, http=8082, peers=[13337, 13339])
# Node 3: Relay (server=13339, http=8083, peers=[13337, 13338])
#
# Node 1 starts first and produces ~10 blocks before Nodes 2 & 3 join.
# All nodes have app-layer enabled for message gossip testing.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$SCRIPT_DIR/build/yaci-node.jar"

# Build if jar doesn't exist
if [ ! -f "$JAR" ]; then
  echo "Building uber-jar..."
  cd "$PROJECT_ROOT"
  ./gradlew :node-app:quarkusBuild -x test
  cd "$SCRIPT_DIR"
fi

if [ ! -f "$JAR" ]; then
  echo "ERROR: $JAR not found after build"
  exit 1
fi

PIDS=()
DATA_DIR=$(mktemp -d /tmp/yaci-multinode-XXXX)
mkdir -p "$DATA_DIR/node1" "$DATA_DIR/node2" "$DATA_DIR/node3"
echo "Data directory: $DATA_DIR"

cleanup() {
  echo ""
  echo "Stopping all nodes..."
  for pid in "${PIDS[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  # Wait up to 5 seconds for graceful shutdown, then force kill
  for i in $(seq 1 5); do
    all_dead=true
    for pid in "${PIDS[@]}"; do
      if kill -0 "$pid" 2>/dev/null; then
        all_dead=false
        break
      fi
    done
    if $all_dead; then break; fi
    sleep 1
  done
  for pid in "${PIDS[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "Force killing PID $pid..."
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
  wait 2>/dev/null
  echo "Removing data directory: $DATA_DIR"
  rm -rf "$DATA_DIR"
  echo "All nodes stopped."
}
trap cleanup EXIT INT TERM

MAGIC=42

# Common options shared by all nodes
COMMON_OPTS=(
  -Dyaci.node.auto-sync-start=true
  -Dyaci.node.storage.rocksdb=true
  -Dyaci.node.remote.protocol-magic=$MAGIC
  -Dyaci.node.server.enabled=true
  -Dyaci.node.app-layer.enabled=true
  -Dyaci.node.app-layer.topics=chat
  -Dyaci.node.app-layer.block-interval-ms=3000
  -Dyaci.node.app-layer.auth-mode=open
)

BLOCK_TIME_MS=2000
WAIT_BLOCKS=10
WAIT_SECS=$(( (BLOCK_TIME_MS * WAIT_BLOCKS / 1000) + 10 ))

echo "=== Starting Node 1 — Block Producer (server=13337, http=8081) ==="
java "${COMMON_OPTS[@]}" \
  -Dquarkus.http.port=8081 \
  -Dyaci.node.server.port=13337 \
  -Dyaci.node.storage.path="$DATA_DIR/node1" \
  -Dyaci.node.client.enabled=true \
  -Dyaci.node.upstreams[0].host=localhost \
  -Dyaci.node.upstreams[0].port=13338 \
  -Dyaci.node.upstreams[0].type=YACI \
  -Dyaci.node.upstreams[1].host=localhost \
  -Dyaci.node.upstreams[1].port=13339 \
  -Dyaci.node.upstreams[1].type=YACI \
  -Dyaci.node.dev-mode=true \
  -Dyaci.node.network=devnet \
  -Dyaci.node.block-producer.enabled=true \
  -Dyaci.node.block-producer.block-time-millis=$BLOCK_TIME_MS \
  -Dyaci.node.block-producer.lazy=false \
  -jar "$JAR" &
PIDS+=($!)

echo ""
echo "Waiting ${WAIT_SECS}s for Node 1 to produce ~${WAIT_BLOCKS} blocks..."
sleep "$WAIT_SECS"

echo ""
echo "=== Starting Node 2 — Relay (server=13338, http=8082) ==="
java "${COMMON_OPTS[@]}" \
  -Dquarkus.http.port=8082 \
  -Dyaci.node.server.port=13338 \
  -Dyaci.node.storage.path="$DATA_DIR/node2" \
  -Dyaci.node.client.enabled=true \
  -Dyaci.node.block-producer.enabled=false \
  -Dyaci.node.app-layer.block-producer-enabled=false \
  -Dyaci.node.upstreams[0].host=localhost \
  -Dyaci.node.upstreams[0].port=13337 \
  -Dyaci.node.upstreams[0].type=YACI \
  -Dyaci.node.upstreams[1].host=localhost \
  -Dyaci.node.upstreams[1].port=13339 \
  -Dyaci.node.upstreams[1].type=YACI \
  -jar "$JAR" &
PIDS+=($!)

echo "=== Starting Node 3 — Relay (server=13339, http=8083) ==="
java "${COMMON_OPTS[@]}" \
  -Dquarkus.http.port=8083 \
  -Dyaci.node.server.port=13339 \
  -Dyaci.node.storage.path="$DATA_DIR/node3" \
  -Dyaci.node.client.enabled=true \
  -Dyaci.node.block-producer.enabled=false \
  -Dyaci.node.app-layer.block-producer-enabled=false \
  -Dyaci.node.upstreams[0].host=localhost \
  -Dyaci.node.upstreams[0].port=13337 \
  -Dyaci.node.upstreams[0].type=YACI \
  -Dyaci.node.upstreams[1].host=localhost \
  -Dyaci.node.upstreams[1].port=13338 \
  -Dyaci.node.upstreams[1].type=YACI \
  -jar "$JAR" &
PIDS+=($!)

echo ""
echo "=== All 3 nodes starting ==="
echo ""
echo "Node 1 is the block producer (devnet, ${BLOCK_TIME_MS}ms block time)."
echo "Nodes 2 & 3 are relays syncing from Node 1."
echo ""
echo "Wait ~15s for relay nodes to sync and gossip connections to establish, then test:"
echo ""
echo "  # Submit message to Node 1"
echo "  curl -X POST http://localhost:8081/api/v1/appmsg/submit \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"topicId\":\"chat\",\"messageBody\":\"Hello from node 1\"}'"
echo ""
echo "  # Check mempools"
echo "  curl http://localhost:8081/api/v1/appmsg/mempool"
echo "  curl http://localhost:8082/api/v1/appmsg/mempool"
echo "  curl http://localhost:8083/api/v1/appmsg/mempool"
echo ""
echo "  # Check blocks (after ~3s app block interval)"
echo "  curl http://localhost:8081/api/v1/appmsg/blocks/chat"
echo ""
echo "  # Status"
echo "  curl http://localhost:8081/api/v1/appmsg/status"
echo ""
echo "Press Ctrl+C to stop all nodes."
echo ""

wait
