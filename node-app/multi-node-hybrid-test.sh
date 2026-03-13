#!/usr/bin/env bash
#
# Hybrid preprod + app-layer test: 3 Yaci relay nodes sync L1 from Cardano/Haskell
# preprod nodes AND gossip app-layer messages between each other via Protocol 100.
#
# Consensus mode: SingleSigner with follower nodes.
#   - Node 1: block producer (proposes + signs blocks)
#   - Node 2: follower (receives finalized blocks, does NOT produce)
#   - Node 3: follower (receives finalized blocks, does NOT produce)
#
# Topology:
#
#                   Cardano Preprod Relay (public:30000)
#                   + localhost:32000 (local Haskell node)
#                       |              |              |
#                  +---------+   +---------+   +---------+
#                  | Node 1  |<->| Node 2  |<->| Node 3  |
#                  | :13337  |   | :13338  |   | :13339  |
#                  | :8081   |   | :8082   |   | :8083   |
#                  | PRODUCER|   | FOLLOWER|   | FOLLOWER|
#                  +---------+   +---------+   +---------+
#
# Handshake: V14 with Cardano nodes, V100 between Yaci nodes.
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
DATA_DIR="$SCRIPT_DIR/hybrid-test"
mkdir -p "$DATA_DIR/node1" "$DATA_DIR/node2" "$DATA_DIR/node3"
echo "Data directory: $DATA_DIR (persistent — reuse across runs)"

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
  echo "All nodes stopped. Data preserved at: $DATA_DIR"
}
trap cleanup EXIT INT TERM

MAGIC=1  # Preprod

# Common options shared by all nodes
COMMON_OPTS=(
  -Dyaci.node.network=preprod
  -Dyaci.node.auto-sync-start=true
  -Dyaci.node.storage.rocksdb=true
  -Dyaci.node.remote.protocol-magic=$MAGIC
  -Dyaci.node.server.enabled=true
  -Dyaci.node.client.enabled=true
  -Dyaci.node.block-producer.enabled=false
  -Dyaci.node.app-layer.enabled=true
  -Dyaci.node.app-layer.topics=chat
  -Dyaci.node.app-layer.auth-mode=open
)

HEALTH_WAIT=60  # seconds to wait for initial L1 sync / health

echo "=== Starting Node 1 (server=13337, http=8081) — PRODUCER ==="
java "${COMMON_OPTS[@]}" \
  -Dquarkus.http.port=8081 \
  -Dyaci.node.server.port=13337 \
  -Dyaci.node.storage.path="$DATA_DIR/node1" \
  -Dyaci.node.app-layer.block-producer-enabled=true \
  -Dyaci.node.upstreams[0].host=preprod-node.world.dev.cardano.org \
  -Dyaci.node.upstreams[0].port=30000 \
  -Dyaci.node.upstreams[0].type=CARDANO \
  -Dyaci.node.upstreams[1].host=localhost \
  -Dyaci.node.upstreams[1].port=32000 \
  -Dyaci.node.upstreams[1].type=CARDANO \
  -Dyaci.node.upstreams[2].host=localhost \
  -Dyaci.node.upstreams[2].port=13338 \
  -Dyaci.node.upstreams[2].type=YACI \
  -Dyaci.node.upstreams[3].host=localhost \
  -Dyaci.node.upstreams[3].port=13339 \
  -Dyaci.node.upstreams[3].type=YACI \
  -jar "$JAR" &
PIDS+=($!)

echo "=== Starting Node 2 (server=13338, http=8082) — FOLLOWER ==="
java "${COMMON_OPTS[@]}" \
  -Dquarkus.http.port=8082 \
  -Dyaci.node.server.port=13338 \
  -Dyaci.node.storage.path="$DATA_DIR/node2" \
  -Dyaci.node.app-layer.block-producer-enabled=false \
  -Dyaci.node.upstreams[0].host=preprod-node.world.dev.cardano.org \
  -Dyaci.node.upstreams[0].port=30000 \
  -Dyaci.node.upstreams[0].type=CARDANO \
  -Dyaci.node.upstreams[1].host=localhost \
  -Dyaci.node.upstreams[1].port=32000 \
  -Dyaci.node.upstreams[1].type=CARDANO \
  -Dyaci.node.upstreams[2].host=localhost \
  -Dyaci.node.upstreams[2].port=13337 \
  -Dyaci.node.upstreams[2].type=YACI \
  -Dyaci.node.upstreams[3].host=localhost \
  -Dyaci.node.upstreams[3].port=13339 \
  -Dyaci.node.upstreams[3].type=YACI \
  -jar "$JAR" &
PIDS+=($!)

echo "=== Starting Node 3 (server=13339, http=8083) — FOLLOWER ==="
java "${COMMON_OPTS[@]}" \
  -Dquarkus.http.port=8083 \
  -Dyaci.node.server.port=13339 \
  -Dyaci.node.storage.path="$DATA_DIR/node3" \
  -Dyaci.node.app-layer.block-producer-enabled=false \
  -Dyaci.node.upstreams[0].host=preprod-node.world.dev.cardano.org \
  -Dyaci.node.upstreams[0].port=30000 \
  -Dyaci.node.upstreams[0].type=CARDANO \
  -Dyaci.node.upstreams[1].host=localhost \
  -Dyaci.node.upstreams[1].port=32000 \
  -Dyaci.node.upstreams[1].type=CARDANO \
  -Dyaci.node.upstreams[2].host=localhost \
  -Dyaci.node.upstreams[2].port=13337 \
  -Dyaci.node.upstreams[2].type=YACI \
  -Dyaci.node.upstreams[3].host=localhost \
  -Dyaci.node.upstreams[3].port=13338 \
  -Dyaci.node.upstreams[3].type=YACI \
  -jar "$JAR" &
PIDS+=($!)

echo ""
echo "=== All 3 nodes starting — waiting ${HEALTH_WAIT}s for L1 sync ==="

PASS=0
FAIL=0
report() {
  local label="$1" result="$2"
  if [ "$result" = "PASS" ]; then
    echo "  [PASS] $label"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $label"
    FAIL=$((FAIL + 1))
  fi
}

# Wait for health endpoints to become ready
sleep "$HEALTH_WAIT"

echo ""
echo "=== Step 1: Health checks ==="
for port in 8081 8082 8083; do
  status=$(curl -sf -o /dev/null -w "%{http_code}" "http://localhost:$port/q/health/ready" 2>/dev/null || echo "000")
  if [ "$status" = "200" ]; then
    report "Node :$port health" "PASS"
  else
    report "Node :$port health (HTTP $status)" "FAIL"
  fi
done

echo ""
echo "=== Step 2: Submit app message to Node 1 (producer) and verify propagation ==="
MSG_BODY="hybrid-test-$(date +%s)"
submit_resp=$(curl -sf -X POST "http://localhost:8081/api/v1/appmsg/submit" \
  -H 'Content-Type: application/json' \
  -d "{\"topicId\":\"chat\",\"messageBody\":\"$MSG_BODY\"}" 2>/dev/null || echo "ERROR")

if [ "$submit_resp" != "ERROR" ]; then
  report "Submit app message to Node 1" "PASS"
else
  report "Submit app message to Node 1" "FAIL"
fi

# Wait for gossip propagation
sleep 5

echo ""
echo "=== Step 3: Verify app message propagation ==="
for port in 8081 8082 8083; do
  mempool=$(curl -sf "http://localhost:$port/api/v1/appmsg/mempool" 2>/dev/null || echo "")
  if echo "$mempool" | grep -q "$MSG_BODY" 2>/dev/null; then
    report "Message visible on :$port" "PASS"
  else
    report "Message visible on :$port (not found in mempool)" "FAIL"
  fi
done

echo ""
echo "=== Step 4: Wait for block production + gossip (10s) ==="
sleep 10

echo ""
echo "=== Step 5: Verify consensus — all nodes have same block #0 ==="
HASHES=()
for port in 8081 8082 8083; do
  block=$(curl -sf "http://localhost:$port/api/v1/appmsg/blocks/chat/0" 2>/dev/null || echo "")
  if [ -n "$block" ] && [ "$block" != "" ]; then
    hash=$(echo "$block" | grep -o '"blockHash":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ -n "$hash" ]; then
      HASHES+=("$hash")
      report "Block #0 present on :$port (hash=${hash:0:16}...)" "PASS"
    else
      HASHES+=("")
      report "Block #0 on :$port (no blockHash in response)" "FAIL"
    fi
  else
    HASHES+=("")
    report "Block #0 on :$port (no response)" "FAIL"
  fi
done

# Compare hashes — all should be the same
if [ "${#HASHES[@]}" -ge 3 ] && [ -n "${HASHES[0]}" ] && \
   [ "${HASHES[0]}" = "${HASHES[1]}" ] && [ "${HASHES[1]}" = "${HASHES[2]}" ]; then
  report "All 3 nodes have identical block #0 hash" "PASS"
else
  report "Block #0 hash mismatch across nodes" "FAIL"
fi

echo ""
echo "=== Step 6: Verify L1 connections alive (no mux errors) ==="
for pid in "${PIDS[@]}"; do
  if kill -0 "$pid" 2>/dev/null; then
    report "Node PID $pid alive" "PASS"
  else
    report "Node PID $pid alive" "FAIL"
  fi
done

echo ""
echo "=========================================="
echo "  RESULTS: $PASS passed, $FAIL failed"
echo "=========================================="

if [ "$FAIL" -gt 0 ]; then
  echo ""
  echo "Some tests FAILED. Leaving nodes running for debugging."
  echo "Press Ctrl+C to stop all nodes."
  wait
else
  echo ""
  echo "All tests PASSED!"
  echo "Press Ctrl+C to stop all nodes, or wait for manual testing."
  echo ""
  echo "Manual test commands:"
  echo "  curl http://localhost:8081/api/v1/appmsg/blocks/chat/0"
  echo "  curl http://localhost:8082/api/v1/appmsg/blocks/chat/0"
  echo "  curl http://localhost:8083/api/v1/appmsg/blocks/chat/0"
  wait
fi
