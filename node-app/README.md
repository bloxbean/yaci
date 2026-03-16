# Yaci Node App

Quarkus-based wrapper for the Yaci node with two built-in profiles:

- **Relay mode** (default) — connects to preprod and serves chain data on port 13337
- **Devnet mode** — standalone block producer with genesis funds and configurable protocol parameters

## Prerequisites

- Java 21

## Build

```bash
./gradlew :node-app:quarkusBuild -x test
```

## Quick Start — Devnet Mode

Runs a local block producer (protocol magic 42) with pre-funded genesis addresses:

```bash
cd node-app
./start-devnet.sh
```

Verify the node is running and producing blocks:

```bash
# Chain tip
curl http://localhost:8080/api/v1/node/tip

# Latest block
curl http://localhost:8080/api/v1/blocks/latest

# Protocol parameters
curl http://localhost:8080/api/v1/epochs/latest/parameters
```

Submit a transaction (hex-encoded CBOR):

```bash
curl -X POST http://localhost:8080/api/v1/tx/submit \
  -H "Content-Type: text/plain" \
  -d '<hex-encoded-tx-cbor>'
```

Submit a transaction (raw CBOR bytes):

```bash
curl -X POST http://localhost:8080/api/v1/tx/submit \
  -H "Content-Type: application/cbor" \
  --data-binary @tx.cbor
```

Query UTXOs for an address:

```bash
curl http://localhost:8080/api/v1/addresses/<address>/utxos
```

Query transaction inputs/outputs:

```bash
curl http://localhost:8080/api/v1/txs/<txHash>/utxos
```

## Quick Start — Relay Mode

Connects to the Cardano preprod network (protocol magic 1) and syncs blocks:

```bash
cd node-app
./start.sh
```

Verify:

```bash
curl http://localhost:8080/api/v1/node/tip
```

## Native Image

Build and run with GraalVM native image:

```bash
./gradlew :node-app:build -Dquarkus.profile=native

cd node-app
./start.sh --native
./start-devnet.sh --native
```

## Configuration

### Profiles

| Profile | Mode | Client | Server | Block Producer | Remote |
|---------|------|--------|--------|----------------|--------|
| *default* | Relay | enabled | enabled (13337) | disabled | preprod (magic 1) |
| `devnet` | Block Producer | disabled | enabled (13337) | enabled | magic 42 |

Activate the devnet profile:

```bash
java -Dquarkus.profile=devnet -jar build/quarkus-app/quarkus-run.jar
```

### Key Properties

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.http.port` | 8080 | REST API port |
| `yaci.node.server.port` | 13337 | N2N server port |
| `yaci.node.remote.host` | preprod-node.world.dev.cardano.org | Upstream relay host |
| `yaci.node.remote.port` | 30000 | Upstream relay port |
| `yaci.node.remote.protocol-magic` | 1 | Network protocol magic |
| `yaci.node.storage.path` | ./chainstate | RocksDB storage directory |
| `yaci.node.block-producer.block-time-millis` | 2000 (1000 in devnet) | Block production interval |
| `yaci.node.block-producer.script-evaluator` | `aiken` | Plutus script evaluator (`aiken` or `scalus`) |

### Script Evaluator

The node supports two Plutus script evaluators for the `/utils/txs/evaluate` endpoint:

| Evaluator | Default in | Description |
|-----------|-----------|-------------|
| `aiken` | JVM (jar) | Uses Aiken's UPLC evaluator via JNA. Does not work with native image. |
| `scalus` | Native image | Pure JVM evaluator. Works in both JVM and native image modes. |

When using `bin/yaci-node.sh`, the script automatically selects the appropriate evaluator:
- **Native binary** → `scalus`
- **JVM (jar)** → `aiken`

To override, set the property in `config/application.yml`:

```yaml
yaci:
  node:
    block-producer:
      script-evaluator: scalus   # or aiken
```

Or pass it as a system property:

```bash
-Dyaci.node.block-producer.script-evaluator=scalus
```

### Config Files

| File | Purpose |
|------|---------|
| `config/shelley-genesis.json` | Shelley genesis (networkMagic, epochLength, initialFunds) |
| `config/byron-genesis.json` | Byron genesis (optional, nonAvvmBalances) |
| `config/protocol-param.json` | Protocol parameters (includes PlutusV1/V2/V3 cost models) |

## Testing

The node-app has three test tiers: unit, integration, and end-to-end (e2e).

### Unit Tests

```bash
./gradlew :node-app:test
```

Runs standard unit tests. The `integration` and `e2e` tags are automatically excluded.

### Integration Tests

```bash
./gradlew :node-app:integrationTest
```

Runs tests tagged with `@Tag("integration")`. These require a running devnet instance (`cd node-app && ./start-devnet.sh`).

### E2E Tests

End-to-end tests exercise the full node-app (transaction submission, script evaluation, REST APIs, devnet features). The tests auto-start a devnet instance via `@QuarkusTest` — no manual setup needed.

**Run (auto-start devnet):**

```bash
./gradlew :node-app:e2eTest
```

**Run against an external instance:**

```bash
./gradlew :node-app:e2eTest -Dyaci.e2e.baseUrl=http://localhost:8080/api/v1/
```

**Test coverage (35 tests across 7 classes):**

| Class | Coverage |
|-------|----------|
| `SimplePaymentE2ETest` | ADA transfers, multi-output transactions, CIP-20 metadata |
| `AlwaysTrueScriptE2ETest` | PlutusV2 always-true script lock and unlock |
| `ReferenceScriptV2E2ETest` | PlutusV2 reference script attach and spend |
| `ReferenceScriptV3E2ETest` | PlutusV3 reference script attach and spend |
| `NativeScriptMintE2ETest` | Native script token minting, transfer, and burn |
| `ApiEndpointsE2ETest` | REST API validation (blocks, UTXOs, transactions, epochs, protocol params) |
| `DevnetApiE2ETest` | Devnet APIs (fund, snapshot/restore, time advance, rollback, genesis download) |

**Adding new e2e tests:**

1. Extend `BaseE2ETest`
2. Annotate the class with `@Tag("e2e")`
3. Implement `getAccountBaseIndex()` returning a unique range (check existing classes to avoid collisions)
4. Use `fundAddress()` and `waitForTransaction()` helpers from `BaseE2ETest`

## REST API Endpoints

All endpoints are under `/api/v1`. Responses match [yaci-store](https://github.com/bloxbean/yaci-store) JSON format where applicable.

### Node Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/node/status` | Node running status |
| POST | `/node/start` | Start the node |
| POST | `/node/stop` | Stop the node |
| GET | `/node/tip` | Current chain tip |
| GET | `/node/config` | Node configuration |
| GET | `/node/protocol-params` | Current protocol parameters |
| POST | `/node/recover` | Recover chain state |

### Blocks

| Method | Path | Description |
|--------|------|-------------|
| GET | `/blocks/latest` | Latest block (height, slot, epoch, txCount, fees, output) |

### Transactions

| Method | Path | Description |
|--------|------|-------------|
| POST | `/tx/submit` | Submit transaction (`application/cbor` or `text/plain` hex) |
| GET | `/txs/{txHash}/utxos` | Transaction inputs and outputs |

### UTXOs

| Method | Path | Description |
|--------|------|-------------|
| GET | `/addresses/{address}/utxos` | UTXOs by address |
| GET | `/utxos/{txHash}/{index}` | Specific UTXO by outpoint |
| GET | `/credentials/{paymentCredential}/utxos` | UTXOs by payment credential |

### Epochs

| Method | Path | Description |
|--------|------|-------------|
| GET | `/epochs/latest` | Current epoch info |
| GET | `/epochs/latest/parameters` | Current protocol parameters |
| GET | `/epochs/{number}/parameters` | Protocol parameters by epoch |

### Devnet

| Method | Path | Description |
|--------|------|-------------|
| POST | `/devnet/rollback` | Trigger a controlled rollback (devnet mode only) |

**Rollback request** — exactly one of `slot`, `blockNumber`, or `count` must be provided:

```bash
# Rollback to a specific slot
curl -X POST http://localhost:8080/api/v1/devnet/rollback \
  -H "Content-Type: application/json" \
  -d '{"slot": 1234}'

# Rollback to a specific block number
curl -X POST http://localhost:8080/api/v1/devnet/rollback \
  -H "Content-Type: application/json" \
  -d '{"blockNumber": 10}'

# Rollback N blocks behind current tip
curl -X POST http://localhost:8080/api/v1/devnet/rollback \
  -H "Content-Type: application/json" \
  -d '{"count": 3}'
```

The rollback goes through the full standard rollback flow: ChainState rollback, RollbackEvent (UTXO delta unwind), and `Rollbackward` notification to connected n2n clients (e.g. yaci-store).

### Other

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status` | Sync status |
| GET | `/q/health/ready` | Health check |
| GET | `/q/swagger-ui` | Swagger UI (interactive API explorer) |

## Swagger UI

An interactive API explorer is available at `/q/swagger-ui` when the node is running. The OpenAPI spec is at `/q/openapi`.

To disable Swagger UI in production, set the environment variable:

```bash
YACI_SWAGGER_UI_ENABLED=false
```

Or pass the system property:

```bash
-Dquarkus.swagger-ui.always-include=false
```

## Integration with yaci-store

To use this node with [yaci-store](https://github.com/bloxbean/yaci-store):

1. Start the devnet node: `./start-devnet.sh`
2. Point yaci-store at `localhost:13337` with protocol magic `42`
3. The following endpoints are compatible with yaci-store's `BFBackendService`:
   - `GET /api/v1/blocks/latest`
   - `GET /api/v1/txs/{txHash}/utxos`
   - `GET /api/v1/addresses/{address}/utxos`
   - `GET /api/v1/epochs/latest/parameters`
   - `POST /api/v1/tx/submit`
