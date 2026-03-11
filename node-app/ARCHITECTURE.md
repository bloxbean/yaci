# Yaci Node App — Architecture & Design

Yaci Node App is a Quarkus-based Cardano relay node built entirely from composable
Java libraries. It connects to **one upstream** Cardano node (client mode) and serves
**many downstream** peers (server mode), synchronizing the chain through a split
header/body pipeline with RocksDB persistence.

Every component — protocol agents, event bus, storage, plugin system — ships as a
standalone library (`core`, `helper`, `node-api`, `node-runtime`, `events-core`).
The node-app is one assembly; you can build your own.

---

## High-Level Architecture

```
                          ┌─────────────────────────────────────────────────────────────────┐
                          │                       Yaci Node App                             │
                          │                                                                 │
  ┌──────────────┐        │  ┌─────────────────────────────────────────────────────────┐    │
  │   Upstream    │ n2n    │  │                   Client Agents                         │    │
  │   Cardano     │◄──────►│  │  ChainSyncAgent ─► HeaderSyncManager                   │    │
  │   Node        │        │  │  BlockfetchAgent ─► BodyFetchManager                    │    │
  │              │        │  │  TxSubmissionAgent   KeepAliveAgent                      │    │
  └──────────────┘        │  └────────────┬────────────────────┬───────────────────────┘    │
                          │               │                    │                            │
                          │               ▼                    ▼                            │
                          │  ┌────────────────────────────────────────────────────────┐     │
                          │  │                     Storage Layer                      │     │
                          │  │                                                        │     │
                          │  │  DirectRocksDBChainState          DefaultUtxoStore      │     │
                          │  │  ├─ blocks, headers               ├─ unspent/spent     │     │
                          │  │  ├─ slot↔number indexes           ├─ address index     │     │
                          │  │  ├─ header_tip / body_tip         ├─ block deltas      │     │
                          │  │  └─ Byron EBB handling            └─ StorageFilterChain │     │
                          │  │                                        │                │     │
                          │  │  DefaultMemPool (FIFO tx queue)        │                │     │
                          │  └────────────────────────────────────────┼────────────────┘     │
                          │                    │                      │                      │
                          │                    ▼                      ▼                      │
                          │  ┌─────────────────────────────────────────────────────────┐    │
                          │  │                      EventBus                           │    │
                          │  │                                                         │    │
                          │  │  BlockReceivedEvent ──► BlockConsensusEvent (vetoable)   │    │
                          │  │  ──► BlockAppliedEvent ──► TipChangedEvent               │    │
                          │  │  TransactionValidateEvent (vetoable) ──► MemPoolTxEvent  │    │
                          │  │  RollbackEvent   SyncStatusChangedEvent   NodeStarted    │    │
                          │  └──────┬──────────────────────┬───────────────────────────┘    │
                          │         │                      │                                │
                          │         ▼                      ▼                                │
                          │  ┌──────────────┐    ┌──────────────────────────┐               │
                          │  │  Plugin SPI   │    │     Server Agents        │               │
                          │  │  (ServiceLoader)│    │                          │    ┌──────┐  │
                          │  │              │    │  ChainSyncServerAgent    │◄──►│ Peer │  │
                          │  │  StorageFilter│    │  BlockFetchServerAgent   │    │  1   │  │
                          │  │  StorageAdapter│   │  TxSubmissionServerAgent │    └──────┘  │
                          │  │  Notifier     │    │  KeepAliveServerAgent    │    ┌──────┐  │
                          │  │  NodePolicy   │    │                          │◄──►│ Peer │  │
                          │  └──────────────┘    └──────────────────────────┘    │  2   │  │
                          │                                                      └──────┘  │
                          │  ┌──────────────────┐    ┌─────────────────────┐     ┌──────┐  │
                          │  │  REST API (:8080) │    │  Block Producer     │◄──►│ ...  │  │
                          │  │  /api/v1/*        │    │  (devnet mode)      │    └──────┘  │
                          │  └──────────────────┘    └─────────────────────┘               │
                          └─────────────────────────────────────────────────────────────────┘
```

---

## Pipeline: Header/Body Split Sync

Header and body fetching run as two independent loops with backpressure control:

```
  Upstream Node
       │
       ▼
  ChainSyncAgent (n2n headers only)
       │
       ▼
  HeaderSyncManager
  ├─ storeBlockHeader() ──► header_tip advances
  ├─ Backpressure: pauses when gap > 50k blocks
  └─ Byron EBB special indexing
       │
       │  gap = header_tip - body_tip
       ▼
  BodyFetchManager (monitors gap every 100-500ms)
  ├─ Requests ranges via BlockfetchAgent
  ├─ storeBlock() ──► body_tip advances
  ├─ Publishes: BlockReceived → Consensus → Applied → TipChanged
  ├─ Stale block detection + corruption recovery
  └─ Sync phases: INITIAL_SYNC (bulk) → STEADY_STATE (tip)
```

**Key invariant:** `header_tip.slot >= body_tip.slot` — headers always lead.

---

## Event System

Type-safe pub/sub built on `events-core` with zero external dependencies.

### Event Flow

```
  Block fetched from network
       │
       ▼
  BlockReceivedEvent ──────────────────────── informational, pre-storage
       │
       ▼
  BlockConsensusEvent (VetoableEvent) ─────── plugins can reject()
       │  accepted?
       ▼
  BlockAppliedEvent ───────────────────────── block stored in ChainState
       │                                      UTXO store processes outputs
       ▼
  TipChangedEvent ─────────────────────────── tip advanced (prev → current)


  Transaction submitted (REST or n2n)
       │
       ▼
  TransactionValidateEvent (VetoableEvent) ── validation plugins can reject()
       │  accepted?                            ordering: 0-49 pre-checks,
       ▼                                       100 ledger-rules, 200+ policy
  MemPoolTransactionReceivedEvent ─────────── tx added to mempool


  Other events:
  ├─ RollbackEvent ────────── chain reorg (real fork or sync correction)
  ├─ SyncStatusChangedEvent ─ IDLE → CATCHING_UP → SYNCED
  ├─ NodeStartedEvent ─────── node initialization complete
  └─ BlockProducedEvent ───── devnet block production
```

### Vetoable Events

Two events implement `VetoableEvent` — listeners call `reject(source, reason)` to
prevent the action. These are dispatched **synchronously only** (async listeners
cannot participate in veto decisions).

| Event | Gate | Listener Order Convention |
|-------|------|--------------------------|
| `BlockConsensusEvent` | Block acceptance into chain | Consensus plugins |
| `TransactionValidateEvent` | Tx admission to mempool | 0-49: pre-checks, 50-99: whitelist, 100: ledger-rules, 200+: policy |

### Subscribing to Events

```java
public class MyPlugin implements NodePlugin {

    @DomainEventListener(order = 100)
    public void onBlock(BlockAppliedEvent event) {
        // process applied block
    }

    @DomainEventListener(async = true)
    public void onTip(EventContext<TipChangedEvent> ctx) {
        EventMetadata meta = ctx.metadata();  // slot, blockNo, origin, etc.
        // async processing on virtual thread
    }
}
```

- `@DomainEventListener(order = N)` — lower N runs first
- `@DomainEventListener(async = true)` — dispatched on virtual thread executor
- Method takes either the event directly or `EventContext<E>` for metadata access
- AOT-friendly: `events-processor` generates bindings at compile time (GraalVM compatible)

---

## Plugin System

Plugins extend node behavior without modifying core code. Discovered via
`ServiceLoader` (`META-INF/services/com.bloxbean.cardano.yaci.node.api.plugin.NodePlugin`).

### Capabilities

```java
public enum PluginCapability {
    EVENT_CONSUMER,    // Listen to blockchain events
    NOTIFIER,          // Send notifications (webhooks, Slack, email)
    STORAGE_ADAPTER,   // Custom persistence (external DBs, message queues)
    POLICY,            // Block acceptance / rollback policy decisions
    STORAGE_FILTER     // Filter which UTXOs get persisted
}
```

### Plugin Lifecycle

```
ServiceLoader.load(NodePlugin.class)
       │
       ▼
  Topological sort by dependsOn()
       │
       ▼
  init(PluginContext) ──── register listeners, services, filters
       │
       ▼
  start() ─────────────── begin active processing
       │
       ▼
  stop() / close() ────── graceful shutdown, release resources
```

Plugin failures are **isolated** — one plugin's error does not affect others.

### PluginContext Services

| Method | Description |
|--------|-------------|
| `eventBus()` | Subscribe to / publish blockchain events |
| `logger()` | Plugin-specific SLF4J logger |
| `config()` | Plugin configuration map |
| `scheduler()` | Shared `ScheduledExecutorService` for background tasks |
| `pluginClassLoader()` | ClassLoader used to load the plugin |
| `registerService(key, service)` | Register a service for inter-plugin communication |
| `getService(key, type)` | Retrieve a service registered by another plugin |
| `registerStorageFilter(filter)` | Add a UTXO output filter to the chain |

### StorageFilter Chain

Filters control which UTXO outputs get persisted. Multiple filters compose into a
chain — **all must accept** for an output to be stored (AND logic, short-circuit on
first rejection).

```
  Block applied
       │
       ▼
  For each tx output:
       │
       ▼
  StorageFilterChain (sorted by priority, lower first)
  ├─ AddressUtxoFilter (priority 50) ─── built-in address/credential filter
  ├─ PluginFilter A (priority 100) ───── custom plugin filter
  └─ PluginFilter B (priority 200) ───── another plugin filter
       │
       ▼
  All accept? ──► persist to DefaultUtxoStore
  Any reject? ──► skip output
```

**Built-in filter** (`AddressUtxoFilter`): configured via YAML to track specific
addresses or payment credentials. When no addresses are configured, it passes
everything through.

```yaml
yaci:
  node:
    filters:
      utxo:
        enabled: true
        addresses: ["addr1qx..."]
        payment-credentials: ["abcd1234..."]
```

---

## Module Overview

All modules ship as standalone libraries on Maven Central (`com.bloxbean.cardano:yaci-*`).

| Module | Purpose | Key Types |
|--------|---------|-----------|
| **core** | Protocol agents, Netty networking, CBOR serialization | `Agent<T>`, `ChainSyncAgent`, `BlockfetchAgent`, `NodeServer`, `ChainState` |
| **helper** | Reactive APIs for common operations | `BlockSync`, `N2NChainSyncFetcher`, `TxSubmissionClient` |
| **events-core** | Framework-agnostic event bus SPI | `EventBus`, `EventListener`, `@DomainEventListener`, `VetoableEvent` |
| **events-processor** | Annotation processor for AOT event bindings | `DomainEventBindings` (generated) |
| **node-api** | Public node interfaces and plugin SPI | `NodePlugin`, `PluginContext`, `StorageFilter`, `UtxoState`, all event types |
| **node-runtime** | Node implementation with RocksDB | `YaciNode`, `DirectRocksDBChainState`, `DefaultUtxoStore`, `BlockProducer` |
| **node-bootstrap-providers** | Fast startup via external APIs | `BlockfrostBootstrapProvider`, `KoiosBootstrapProvider` |
| **ledger-rules** | Transaction validation interfaces | `TransactionValidator`, `TransactionEvaluator` |
| **scalus-bridge** | Plutus script evaluation via Scalus | `ScalusBasedTransactionValidator`, `ScalusBasedTransactionEvaluator` |
| **node-app** | Quarkus REST application wrapper | JAX-RS resources, health checks, CDI producers |

**Using as libraries** — to build a custom node, depend on `node-api` + `node-runtime`
and wire `YaciNode` directly:

```java
var config = YaciNodeConfig.preprodDefault();
var node = new YaciNode(config, runtimeOptions);
node.start();
```

---

## REST API

Base URL: `http://localhost:8080`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/node/status` | GET | Sync status, tip, phase |
| `/api/v1/node/start` | POST | Start the node |
| `/api/v1/node/stop` | POST | Stop the node |
| `/api/v1/node/tip` | GET | Current chain tip |
| `/api/v1/node/config` | GET | Node configuration |
| `/api/v1/node/protocol-params` | GET | Current protocol parameters |
| `/api/v1/node/tx/submit` | POST | Submit transaction (CBOR) |
| `/api/v1/node/recover` | POST | Recover from corruption |
| `/api/v1/blocks/{hashOrNumber}` | GET | Block by hash or number |
| `/api/v1/blocks/latest` | GET | Latest block |
| `/api/v1/txs/{txHash}` | GET | Transaction details |
| `/api/v1/txs/{txHash}/utxos` | GET | Transaction inputs/outputs |
| `/api/v1/addresses/{addr}/utxos` | GET | UTXOs by address (paginated) |
| `/api/v1/addresses/{addr}/utxos/{asset}` | GET | UTXOs by address + asset |
| `/api/v1/utxos/{txHash}/{index}` | GET | UTXO by outpoint |
| `/api/v1/credentials/{cred}/utxos` | GET | UTXOs by payment credential |
| `/api/v1/epochs/latest` | GET | Current epoch |
| `/api/v1/epochs/{n}/parameters` | GET | Protocol params by epoch |
| `/api/v1/genesis` | GET | Genesis parameters |
| `/api/v1/tx/submit` | POST | Blockfrost-compatible tx submit |
| `/api/v1/utils/txs/evaluate` | POST | Evaluate Plutus scripts (Ogmios-compatible) |
| `/q/health/ready` | GET | Readiness health check |
| `/q/swagger-ui` | GET | Swagger UI |

**Devnet-only** (requires `dev-mode: true`):

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/devnet/rollback` | POST | Rollback chain |
| `/api/v1/devnet/snapshot` | POST | Create snapshot |
| `/api/v1/devnet/restore/{name}` | POST | Restore snapshot |
| `/api/v1/devnet/snapshots` | GET | List snapshots |
| `/api/v1/devnet/fund` | POST | Faucet — fund an address |
| `/api/v1/devnet/time/advance` | POST | Advance time |
| `/api/v1/devnet/genesis/download` | GET | Download genesis files (ZIP) |

---

## Configuration Modes

### Relay (default)

Connects to one upstream peer and serves downstream connections.

```yaml
yaci:
  node:
    network: preprod          # mainnet | preprod | preview
    client:
      enabled: true
    server:
      enabled: true
      port: 13337
    storage:
      rocksdb: true
      path: ./chainstate
```

### Devnet (block producer)

Self-contained network that produces Conway-era blocks. Used for local development
and testing with yaci-store.

```yaml
yaci:
  node:
    network: devnet
    dev-mode: true
    client:
      enabled: false
    server:
      enabled: true
    block-producer:
      enabled: true
      block-time-millis: 1000
      tx-evaluation: true     # Plutus script evaluation via Scalus
    storage:
      rocksdb: true
```

### Bootstrap (lightweight startup)

Skips historical sync by loading recent state from Blockfrost or Koios, then syncs
forward from a recent block.

```yaml
yaci:
  node:
    bootstrap:
      enabled: true
      provider: blockfrost    # blockfrost | koios
      api-key: ${BLOCKFROST_API_KEY}
      block-number: 0         # 0 = latest
```

---

## Extension Points Summary

| Extension Point | Mechanism | Use Case |
|----------------|-----------|----------|
| **Events** | `@DomainEventListener` on plugin methods | React to blocks, txs, rollbacks, tip changes |
| **StorageFilter** | `PluginContext.registerStorageFilter()` | Selective UTXO persistence (address tracking, pruning) |
| **StorageAdapter** | `PluginCapability.STORAGE_ADAPTER` | Export data to external DBs, Kafka, webhooks |
| **NodePolicy** | `PluginCapability.POLICY` | Custom block acceptance / rollback rules |
| **Notifier** | `PluginCapability.NOTIFIER` | Alerts via Slack, email, webhooks |
| **TransactionValidator** | `ledger-rules` SPI | Custom transaction validation rules |
| **VetoableEvent** | `BlockConsensusEvent`, `TransactionValidateEvent` | Reject blocks or transactions before acceptance |
| **ServiceLoader plugins** | JAR in `plugins/` directory | Drop-in extensibility without recompilation |
