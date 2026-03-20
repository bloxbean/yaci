# Yaci Devnet Modes

Yaci node-app can run as a standalone devnet with its own block producer. This document covers the different operating modes and how to start a Haskell cardano-node as a downstream peer.

## Prerequisites

```bash
# Build the uber-jar
./gradlew :node-app:quarkusBuild

# The jar is at: node-app/build/yaci-node.jar
```

Java 21 is required.

## Mode 1: Regular Devnet (Block Producer)

Standard devnet mode. Produces blocks continuously from slot 0, one block per slot at the configured interval.

### Start

```bash
cd node-app
rm -rf chainstate    # fresh start

java -Dquarkus.profile=devnet \
     -Dquarkus.http.port=7070 \
     -jar build/yaci-node.jar
```

### What happens

1. Node starts with server on port 13337 (n2n) and REST API on port 7070
2. Genesis block produced at slot 0
3. Blocks produced every 200ms (auto-derived from `slotLength=0.2s`, `activeSlotsCoeff=1.0`)
4. Wall-clock slot assignment: `slot = (now - genesisTimestamp) / slotLength`

### Configuration

All config is in `application.yml` under the `%devnet` profile. Key properties:

| Property | Default | Description |
|----------|---------|-------------|
| `yaci.node.server.port` | 13337 | N2N server port |
| `yaci.node.remote.protocol-magic` | 42 | Network magic for devnet |
| `yaci.node.block-producer.enabled` | true | Enable block production |
| `yaci.node.block-producer.block-time-millis` | 0 (auto) | Block interval; 0 = derive from genesis |
| `yaci.node.block-producer.lazy` | false | If true, skip empty blocks |
| `yaci.node.block-producer.genesis-timestamp` | 0 (auto) | Genesis time; 0 = use current time |
| `yaci.node.block-producer.slot-length-millis` | 0 (auto) | Slot length; 0 = derive from genesis |
| `yaci.node.dev-mode` | true | Enables devnet REST APIs |

Genesis files are at `config/network/devnet/`.

### Verify

```bash
# Check tip
curl -s http://localhost:7070/api/v1/node/tip

# Health check
curl -s http://localhost:7070/q/health/ready
```

---

## Mode 2: Past Time Travel Mode

Allows creating a chain with blocks spanning multiple past epochs before syncing with real time. Useful for governance testing where transactions must be submitted across epoch boundaries.

### How it works

1. Node starts but **defers block production** until `/epochs/shift` is called
2. `/epochs/shift` shifts the genesis timestamp back by N epochs, then starts producing blocks sequentially from slot 0
3. Blocks are produced at slots 0, 1, 2, 3... (sequential, not wall-clock)
4. You can submit transactions and advance time by epochs using `/time/advance`
5. `/epochs/catch-up` rapidly produces blocks to reach the current wall-clock slot, then switches to normal wall-clock block production

### Start

```bash
cd node-app
rm -rf chainstate

java -Dquarkus.profile=devnet \
     -Dquarkus.http.port=7070 \
     -Dyaci.node.block-producer.past-time-travel-mode=true \
     -jar build/yaci-node.jar
```

### Workflow

**Step 1: Shift genesis back N epochs**

```bash
curl -s -X POST http://localhost:7070/api/v1/devnet/epochs/shift \
  -H 'Content-Type: application/json' \
  -d '{"epochs": 4}'
```

Response:
```json
{
  "message": "Shifted genesis back by 4 epochs and started block producer",
  "shift_millis": 480000,
  "new_system_start": "2026-03-21T04:02:27.990Z",
  "genesis_slot": 0
}
```

Block production starts: genesis at slot 0, then slots 1, 2, 3... every 200ms.

**Step 2: Submit transactions (optional)**

Submit transactions via the standard tx submission endpoint. They will be included in the next block.

```bash
curl -s -X POST http://localhost:7070/api/v1/tx/submit \
  -H 'Content-Type: application/cbor' \
  --data-binary @tx.cbor
```

**Step 3: Advance time by epochs**

```bash
# Advance 1 epoch (600 slots with default config)
curl -s -X POST http://localhost:7070/api/v1/devnet/time/advance \
  -H 'Content-Type: application/json' \
  -d '{"epochs": 1}'
```

You can also advance by slots or seconds:
```bash
# By slots
curl -s -X POST http://localhost:7070/api/v1/devnet/time/advance \
  -H 'Content-Type: application/json' \
  -d '{"slots": 600}'

# By seconds
curl -s -X POST http://localhost:7070/api/v1/devnet/time/advance \
  -H 'Content-Type: application/json' \
  -d '{"seconds": 120}'
```

**Step 4: Catch up to wall-clock time**

After all epoch shifts and transaction injections are done:

```bash
curl -s -X POST http://localhost:7070/api/v1/devnet/epochs/catch-up
```

Response:
```json
{
  "message": "Caught up to wall-clock: 2408 blocks produced",
  "new_slot": 2515,
  "new_block_number": 2515,
  "blocks_produced": 2408
}
```

After catch-up, the node switches to normal wall-clock block production.

### Example: 4-epoch governance test

```bash
# Shift genesis back 4 epochs
curl -s -X POST http://localhost:7070/api/v1/devnet/epochs/shift \
  -H 'Content-Type: application/json' -d '{"epochs": 4}'

# Submit governance proposal tx
curl -s -X POST http://localhost:7070/api/v1/tx/submit \
  -H 'Content-Type: application/cbor' --data-binary @proposal.cbor

# Advance 1 epoch
curl -s -X POST http://localhost:7070/api/v1/devnet/time/advance \
  -H 'Content-Type: application/json' -d '{"epochs": 1}'

# Submit votes
curl -s -X POST http://localhost:7070/api/v1/tx/submit \
  -H 'Content-Type: application/cbor' --data-binary @vote.cbor

# Advance remaining 3 epochs
curl -s -X POST http://localhost:7070/api/v1/devnet/time/advance \
  -H 'Content-Type: application/json' -d '{"epochs": 3}'

# Catch up to real time
curl -s -X POST http://localhost:7070/api/v1/devnet/epochs/catch-up

# Now the chain has governance actions spanning 4 epochs
# and is producing blocks at real-time pace
```

---

## Connecting a Haskell Cardano Node

A Haskell cardano-node (10.5.x) can sync from a Yaci devnet as a downstream peer.

### Prerequisites

- Haskell cardano-node binary (10.5.x recommended)
- Matching genesis files (must copy from running Yaci node)
- Configuration file with `Test*HardForkAtEpoch: 0` for all eras (instant Conway)

### Setup

The compatibility test environment is at:
```
/Users/satya/work/cardano-node/compatibility-node-test/haskell-node/
```

**1. Copy genesis files from running Yaci node**

This must be done while Yaci is running, because in past-time-travel mode the `systemStart` is updated dynamically:

```bash
bash /Users/satya/work/cardano-node/compatibility-node-test/scripts/copy-devnet-genesis.sh
```

Or manually:
```bash
cp node-app/config/network/devnet/shelley-genesis.json <haskell-node>/files/
cp node-app/config/network/devnet/byron-genesis.json   <haskell-node>/files/
cp node-app/config/network/devnet/alonzo-genesis.json  <haskell-node>/files/
cp node-app/config/network/devnet/conway-genesis.json  <haskell-node>/files/
```

**2. Verify systemStart matches**

```bash
grep systemStart <haskell-node>/files/shelley-genesis.json
```

**3. Topology**

The Haskell node topology should point to Yaci's n2n port:

```json
{
  "bootstrapPeers": [
    {"address": "127.0.0.1", "port": 13337}
  ],
  "localRoots": [
    {
      "accessPoints": [
        {"address": "127.0.0.1", "port": 13337}
      ],
      "valency": 1
    }
  ],
  "publicRoots": [],
  "useLedgerAfterSlot": -1
}
```

**4. Configuration**

Key settings in `configuration.json`:
```json
{
  "Protocol": "Cardano",
  "RequiresNetworkMagic": "RequiresMagic",
  "EnableP2P": true,
  "TestShelleyHardForkAtEpoch": 0,
  "TestAllegraHardForkAtEpoch": 0,
  "TestMaryHardForkAtEpoch": 0,
  "TestAlonzoHardForkAtEpoch": 0,
  "TestBabbageHardForkAtEpoch": 0,
  "TestConwayHardForkAtEpoch": 0,
  "ExperimentalHardForksEnabled": true
}
```

**5. Start Haskell node**

```bash
cd <haskell-node>
rm -rf db && mkdir -p db

./bin/cardano-node run \
  --topology files/topology.json \
  --database-path db \
  --socket-path db/node.socket \
  --host-addr 0.0.0.0 \
  --port 3002 \
  --config configuration.json
```

**6. Verify sync**

Look for "Chain extended" messages in the Haskell node output. Query the tip:

```bash
CARDANO_NODE_SOCKET_PATH=db/node.socket ./bin/cardano-cli query tip --testnet-magic 42
```

---

## Devnet REST API Reference

All endpoints require `yaci.node.dev-mode=true` and are under `/api/v1/devnet/`.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/epochs/shift` | Shift genesis back N epochs (past-time-travel only) |
| POST | `/epochs/catch-up` | Catch up to wall-clock slot (past-time-travel only) |
| POST | `/time/advance` | Advance time by slots, seconds, or epochs |
| POST | `/rollback` | Rollback to a slot, block number, or by count |
| POST | `/fund` | Fund an address with ADA |
| POST | `/snapshot` | Create a named chain state snapshot |
| POST | `/restore/{name}` | Restore a snapshot |
| GET | `/snapshots` | List all snapshots |
| DELETE | `/snapshot/{name}` | Delete a snapshot |
| GET | `/genesis/download` | Download genesis files as ZIP |

General endpoints:
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/node/tip` | Current chain tip |
| POST | `/api/v1/tx/submit` | Submit a transaction (CBOR body) |
| GET | `/q/health/ready` | Health check |
| GET | `/q/swagger-ui` | Swagger UI |

---

## Default Genesis Parameters

| Parameter | Value | Effect |
|-----------|-------|--------|
| `networkMagic` | 42 | Devnet magic number |
| `epochLength` | 600 | Slots per epoch |
| `slotLength` | 0.2 | Seconds per slot |
| `activeSlotsCoeff` | 1.0 | Every slot produces a block |
| Epoch duration | 120s | 600 slots x 0.2s |
| Block interval | 200ms | Derived from slotLength x activeSlotsCoeff |
