# ADR-006: UTXO State — Performance Improvement Plan

Status: Updated (Async apply implemented)

Date: 2025-09-11

---

## Context

With UTXO state disabled, syncing ~100 blocks typically completes in a few hundred milliseconds to < 1s. With UTXO state enabled (Classic store), the same span takes ~3s. Logs show `BodyFetchManager` pacing block processing much slower when UTXO writes are active.

Observed causes in current implementation (ClassicUtxoStore + DirectRocksDBChainState):
- Event handling runs synchronously on the publisher thread. Heavy UTXO work blocks `BodyFetchManager`.
- Per-input point lookups (`db.get`) and CBOR decode happen for each spent/collateral input.
- Pruning runs at every block, scanning CFs from the beginning and CBOR-decoding to compute cutoffs.
- RocksDB is opened with near-default options: no pipelined writes, no prefix extractor or filters for `utxo_addr`, default compression, etc.
- Spent recording encodes CBOR (wraps prior unspent) per spent input.

This ADR proposes a staged plan to recover headroom without compromising atomicity, ordering, or crash safety.

---

## Goals

- Reduce per-block latency and increase block throughput when UTXO is enabled.
- Maintain strict apply/rollback ordering and per-block atomicity.
- Preserve crash safety and deterministic recovery.
- Keep APIs stable and backward-compatible for users.

## Non-Goals

- Changing external REST/query API semantics.
- Altering chainstate persistence or network protocols.

---

## High-Level Approach (Refined)

Profiling indicates pruning is the primary throughput bottleneck. We will:

1) Move pruning off the hot path immediately using a virtual‑thread scheduler and persisted cursors.
2) Split orchestration from persistence (handler vs. store writer) while keeping synchronous delivery to avoid reconciliation complexity.
3) Add an optional async apply mode later (single‑thread ordered executor) with a clear recovery story, guarded by config.
4) Convert blocks into compact execution plans with MultiGet prefetch (planner) to reduce JNI/CBOR overhead.
5) Tune RocksDB options (pipelined writes, ZSTD, prefix bloom) aligned with access patterns.
6) Optionally reduce encoding in the hot path (self‑contained deltas or lightweight spent encoding; unspent meta header/CF).

---

## Architecture Changes

- UtxoEventHandler (orchestrator, baseline):
  - Sole subscriber to `BlockAppliedEvent` and `RollbackEvent`.
  - Baseline: invoke `UtxoProcessor` + `UtxoStoreWriter` synchronously (no internal queue), preserving current ordering semantics and avoiding reconciliation complexity.

- Optional Async Apply Mode (later phase):
  - A single‑thread ordered executor processes apply/rollback tasks off the publisher thread.
  - Strict ordering guaranteed by the single worker; feature is opt‑in via config.

- UtxoProcessor (planner):
  - Builds `ApplyPlan` from a block: aggregates inputs (incl. collaterals) and outputs, does a single `multiGet` for all inputs, avoids repeated CBOR work.
  - Builds `UndoPlan` from a decoded delta for rollback.

- UtxoStoreWriter (persistence):
  - Executes `ApplyPlan`/`UndoPlan` in a single `RocksDB.WriteBatch` (WAL enabled).
  - Writes `utxo_meta.lastAppliedBlock`/`lastAppliedSlot` in the same batch.

- UtxoState (read API):
  - Remains read-only. Implementations (Classic/MMR) share the same RocksDB.

- UtxoStoreFactory (wiring):
  - Creates Classic or MMR backend; provides `UtxoStoreWriter` + `UtxoState` to `UtxoEventHandler`.

- PruneService (scheduler):
  - Runs pruning on a schedule using a virtual‑thread based `ScheduledExecutorService`.
  - Uses persisted cursors in `utxo_meta` to avoid re‑scanning from the beginning; bounded work per tick.

---

## Atomicity, Ordering, and Recovery

- Ordering (baseline): EventBus delivers synchronously; handler calls writer directly → same ordering as today.
- Ordering (optional async mode): Single worker thread serializes tasks in publication order.
- Atomicity: Each block’s changes commit via a single `WriteBatch` across CFs (atomic); WAL remains enabled by default. Consider `atomic_flush=true` at DB level for stronger cross-CF flush.
- Recovery: `UtxoStoreWriter` updates `utxo_meta.lastAppliedBlock/Slot` in the same batch. On startup:
  - If `lastAppliedBlock < chain tip`, replay forward by rebuilding `ApplyPlan`s (idempotent operations).
  - If `lastAppliedBlock > chain tip` (post-fork crash), roll back using `utxo_block_delta` by building `UndoPlan`s.
- Idempotence: Spending an already-missing outpoint and re-putting the same keys are safe no-ops.
- Prune Safety: Retain `spent`/`delta` windows ≥ rollback window; prune via cursors to avoid re-scanning entire CFs.

---

## RocksDB Tuning (Targets)

DBOptions (global):
- `enablePipelinedWrite = true`
- `allowConcurrentMemtableWrite = true`
- `increaseParallelism = cores`
- `atomic_flush = true` (recommended)

CF Options:
- `utxo_unspent`, `utxo_spent` (point lookups):
  - BlockBasedTableConfig with bloom (≈10 bits/key), whole-key filtering, pin L0 index/filter in cache.
  - Compression `ZSTD`; moderate block cache.
- `utxo_addr` (prefix scans):
  - `FixedPrefixTransform(28)`, `memtable_prefix_bloom_size_ratio≈0.1`, table bloom≈10, `whole_key_filtering=false`, partitioned filters.
- `utxo_block_delta` (seq writes):
  - Defaults + `ZSTD` compression.

Read/Write Options (hot path):
- Reads: `ReadOptions.setFillCache(false).setVerifyChecksums(false)` for apply/rollback/prune scans.
- Writes: reuse `WriteOptions`; optional `disableWAL=true` as an operator tuning (default false).

---

## Data-Path Optimizations

- MultiGet Prefetch: For each block, gather all outpoints to spend (incl. collaterals), call `db.multiGetAsList(cfUnspent, keys)` once.
- Cheap Spent Encoding (option): Store a tiny header + raw prior unspent bytes in `utxo_spent`, or prefer `delta.selfContained=true` to drop `utxo_spent` writes entirely.
- Avoid CBOR for Deletes: Include a fixed header before CBOR in `utxo_unspent` (e.g., `createdSlot|addrHash28|payCred28?`) or maintain a companion `utxo_unspent_meta` so index deletes don’t require CBOR decoding.
- Prune Scheduler + Cursors: Persist `deltaPruneCursor` and `spentPruneCursor` in `utxo_meta`; execute bounded work per tick.

---

## Java Skeletons (Non-Functional)

```java
// PruneService.java (scheduler; virtual threads)
public final class PruneService implements AutoCloseable {
    private final ScheduledExecutorService ses; // virtual thread scheduler
    private final UtxoStoreWriter writer;
    private final long intervalMs;

    public PruneService(UtxoStoreWriter writer, long intervalMs) {
        this.writer = writer;
        this.intervalMs = intervalMs;
        this.ses = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    }

    public void start() {
        ses.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void tick() {
        // Bounded prune using cursors in utxo_meta, outside apply path.
        // Respect rollback/prune windows and write small batches.
        writer.pruneOnce();
    }

    @Override public void close() { ses.shutdownNow(); }
}
```

```java
// UtxoEventHandler.java (orchestrator; baseline synchronous)
public final class UtxoEventHandler {
    private final UtxoProcessor processor;
    private final UtxoStoreWriter writer;

    public UtxoEventHandler(UtxoProcessor processor, UtxoStoreWriter writer) {
        this.processor = processor;
        this.writer = writer;
    }

    @com.bloxbean.cardano.yaci.events.api.DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) {
        if (e.block() == null) return;
        var ctx = BlockCtx.of(e.era(), e.slot(), e.blockNumber(), e.blockHash(), e.block());
        var plan = processor.buildApplyPlan(ctx);
        writer.apply(plan, Meta.of(ctx.blockNumber(), ctx.slot(), ctx.blockHash()));
    }

    @com.bloxbean.cardano.yaci.events.api.DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) {
        long targetSlot = e.target().getSlot();
        for (Delta dec : writer.readDeltasAfter(targetSlot)) {
            var up = processor.buildUndoPlan(dec);
            writer.rollback(up, Meta.of(dec.blockNumber(), dec.slot(), null));
        }
    }
}
```

```java
// UtxoEventHandlerAsync.java (optional async apply mode; ordered single worker)
public final class UtxoEventHandlerAsync implements AutoCloseable {
    private final java.util.concurrent.ExecutorService single;
    private final UtxoProcessor processor;
    private final UtxoStoreWriter writer;

    public UtxoEventHandlerAsync(UtxoProcessor processor, UtxoStoreWriter writer) {
        this.processor = processor;
        this.writer = writer;
        this.single = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "utxo-apply-1"));
    }

    @com.bloxbean.cardano.yaci.events.api.DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) { single.execute(() -> handleApply(e)); }

    @com.bloxbean.cardano.yaci.events.api.DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) { single.execute(() -> handleRollback(e)); }

    private void handleApply(BlockAppliedEvent e) {
        if (e.block() == null) return;
        var ctx = BlockCtx.of(e.era(), e.slot(), e.blockNumber(), e.blockHash(), e.block());
        var plan = processor.buildApplyPlan(ctx);
        writer.apply(plan, Meta.of(ctx.blockNumber(), ctx.slot(), ctx.blockHash()));
    }

    private void handleRollback(RollbackEvent e) {
        long targetSlot = e.target().getSlot();
        for (Delta dec : writer.readDeltasAfter(targetSlot)) {
            var up = processor.buildUndoPlan(dec);
            writer.rollback(up, Meta.of(dec.blockNumber(), dec.slot(), null));
        }
    }

    @Override public void close() { single.shutdownNow(); }
}
```

```java
// UtxoProcessor.java (planner)
public interface UtxoProcessor {
    ApplyPlan buildApplyPlan(BlockCtx ctx);
    UndoPlan buildUndoPlan(Delta delta);
}

public record BlockCtx(Era era, long slot, long blockNumber, String blockHash, Block block) {
    static BlockCtx of(Era era, long slot, long blockNumber, String hash, Block block) {
        return new BlockCtx(era, slot, blockNumber, hash, block);
    }
}

public record ApplyPlan(List<Outpoint> toSpend,
                        List<CreatedOutput> toCreate,
                        List<OutRef> createdRefs,
                        List<OutRef> spentRefs,
                        byte[] deltaValue /* encoded */) {}

public record UndoPlan(List<OutRef> deleteCreated,
                       List<RestoredUnspent> restoreSpent,
                       byte[] deleteDeltaKey) {}

public record Outpoint(String txHash, int index) {}
public record OutRef(String txHash, int index) {}
public record CreatedOutput(String address, java.math.BigInteger lovelace,
                            List<com.bloxbean.cardano.yaci.core.model.Amount> assets,
                            String datumHash, byte[] inlineDatum,
                            String scriptRef, boolean collateralReturn) {}
public record RestoredUnspent(byte[] outpointKey, byte[] unspentValue) {}
```

```java
// UtxoStoreWriter.java (persistence)
public interface UtxoStoreWriter extends AutoCloseable {
    void apply(ApplyPlan plan, Meta meta);
    void rollback(UndoPlan plan, Meta meta);
    Iterable<Delta> readDeltasAfter(long slotExclusive);
}

public record Meta(long blockNumber, long slot, String blockHash) {
    static Meta of(long blockNumber, long slot, String blockHash) { return new Meta(blockNumber, slot, blockHash); }
}

public record Delta(long blockNumber, long slot, List<OutRef> created, List<OutRef> spent) {}
```

```java
// UtxoStoreFactory.java (wiring)
public final class UtxoStoreFactory {
    public static Pair<UtxoStoreWriter, UtxoState> create(RocksDbSupplier rocks,
                                                          Map<String,Object> cfg,
                                                          java.util.logging.Logger log) {
        // select classic|mmr based on cfg and return writer + reader
        throw new UnsupportedOperationException("skeleton");
    }
}
```

---

## Phases & Deliverables (Reordered)

- Phase 1 — Prune Offload First (Virtual Threads + Cursors)
  - Introduce `PruneService` running on a virtual‑thread scheduled executor.
  - Persist `deltaPruneCursor` and `spentPruneCursor` in `utxo_meta` and advance them with bounded work per tick.
  - Remove prune from apply path; respect rollback/prune windows; add prune metrics.

- Phase 2 — Orchestration Split A1 (Synchronous Handler)
  - Split `ClassicUtxoStore` into `UtxoEventHandler` (subscriptions) and `UtxoStoreWriter` (persistence) with `UtxoProcessor` (planner) interface.
  - Keep synchronous delivery (no internal queue). Write `utxo_meta.lastAppliedBlock/Slot` within the apply batch.
  - Add startup reconciliation (forward replay/rollback) — same as current, no ordering change.

- Phase 3 — Optional Async Apply Mode (Single‑Thread, Ordered)
  - Add `UtxoEventHandlerAsync` as an opt‑in feature (config `yaci.node.utxo.applyAsync=true`).
  - Offload apply/rollback to a single ordered worker thread; preserve ordering; keep recovery via meta and deltas.
  - Expose lag metrics; default remains synchronous to avoid reconciliation complexity.

- Phase 4 — Planner + MultiGet Prefetch
  - Implemented `UtxoProcessor` with per-block `ApplyContext` that prefetches all inputs/collaterals via `multiGet` and exposes a lookup to the writer.
  - Writer uses the context for input lookups in `applyBlock`, reducing JNI crossings and cache pollution.
  - Future: extend to full ApplyPlan/UndoPlan if/when beneficial.

- Phase 5 — Status API + UI (NEXT)
  - Status API: `GET /api/v1/status` returns chain tip, UTXO lastApplied, lag blocks, UTXO enabled/store, prune config + cursors (hex), and per-CF estimates (keys) when available.
  - Extended: `GET /api/v1/status/rocksdb-stats?cf=` to expose on-demand or last-sampled RocksDB stats (guarded to avoid heavy calls).
  - Status UI: `/ui/status` lightweight page (HTML+JS) showing:
    - Lag chart (utxo.lag.blocks over time), apply/rollback/prune timings (sparklines placeholder), CF key estimates (bar), sync phase chip.
    - Polls `/api/v1/status` every N seconds (configurable refresh interval).
  - Config: `yaci.node.status.refresh.seconds` (UI polling), `yaci.node.metrics.sample.rocksdb.seconds` (if sampling is used), enable/disable heavy stats endpoint.

- Phase 6 — Metrics & Prometheus (Micrometer)
  - Expose Prometheus endpoint in node-app; wire timers/counters (apply/rollback/prune), gauges (lag blocks, lastApplied, tip), and sampled RocksDB properties.
  - Optional Grafana dashboard JSON with the same panels as the Status UI.

- Phase 7 — RocksDB Options Tuning
  - DBOptions: pipelined writes, concurrent memtable, parallelism, atomic_flush.
  - CF table configs by access pattern; ZSTD; prefix bloom for `utxo_addr`.
  - Hot-path Read/Write options (fillCache=false for scans/lookups).
  - Self‑contained delta (drop `utxo_spent`) OR lightweight spent header+payload.
  - Add unspent header or `utxo_unspent_meta` to avoid CBOR decode on deletes; gate via `schemaVersion`.

- Phase 7 — Observability & SLOs
  - Apply/rollback/prune timers; created/spent/pruned counters; queue/lag gauges; benchmarks.

- Phase 8 — MMR Backend (Parallel)
  - MMR writer/reader wired through the same handler + processor abstractions.

---

## Configuration (Additions)

- `yaci.node.utxo.applyAsync` (default: false)
- `yaci.node.utxo.applyQueueSize` (default: 1024) // only for async mode
- `yaci.node.utxo.prune.schedule.seconds` (default: 5)
- `yaci.node.utxo.disableWal` (default: false)
- `yaci.node.utxo.delta.selfContained` (default: false)
- `yaci.node.utxo.index.address_hash`, `yaci.node.utxo.index.payment_credential` (existing)
- `yaci.node.utxo.pruneDepth`, `yaci.node.utxo.rollbackWindow`, `yaci.node.utxo.pruneBatchSize` (existing)

---

## Testing Strategy

- Unit: planner builds correct Apply/Undo plans; idempotence tests; prune cursors advance correctly.
- Integration: sync with UTXO enabled from a known snapshot; compare query parity vs current impl.
- Replay/Recovery: crash/restart simulations around commit; forward replay and rollback alignment.
- Bench: measure per-100-block times before/after phases; address scan and outpoint lookup throughput.

---

## Risks & Mitigations

- Event backlog growth (async mode): single ordered worker; metrics + alerts on lag; default off.
- Prune/starvation: dedicated scheduler, bounded work per tick; cursors persisted.
- Schema changes: gate optional format changes behind `schemaVersion`; migration routine.
- Tuning regressions: keep operator toggles for WAL and delta mode; document defaults and fallbacks.

---

## Acceptance Criteria

- With UTXO enabled, 100-block windows process within ~1s on dev hardware (target), without correctness regressions.
- No gaps after crash/restart; `lastAppliedBlock` reconciles to chain tip or rolls back deterministically.
- Pruning runs outside hot path; rollback within window remains safe.
- Metrics show improved apply latency and stable queue depth; address/outpoint queries unaffected or improved.

---

## Implementation Status

- Phase 1 — Prune Offload (DONE)
  - Implemented `PruneService` with virtual-thread scheduler.
  - Removed inline prune call from apply path in `ClassicUtxoStore`.
  - Added `ClassicUtxoStore.pruneOnce()` with persisted cursors in `utxo_meta`:
    - `prune.delta.cursor` scans `utxo_block_delta` sequentially up to cutoff.
    - `prune.spent.cursor` scans `utxo_spent` in bounded slices and wraps.
  - Persist `meta.last_applied_slot` and `meta.last_applied_block` during apply for safe cutoffs.
  - Wired `PruneService` in `YaciNode` with config `yaci.node.utxo.prune.schedule.seconds` (default 5s).

- Phase 2 — Orchestration Split A1 (DONE)
  - Introduced `UtxoStoreWriter` and `Prunable` interfaces.
  - Extracted event subscription into `UtxoEventHandler` (synchronous) delegating to writer.
  - Adapted `ClassicUtxoStore` to implement `UtxoStoreWriter` + `Prunable` and removed direct event registration.
  - Updated `PruneService` to depend on `Prunable`.
  - Wired in `YaciNode` and tests.
  - Added startup reconciliation: writer compares `meta.last_applied_*` to chain tip; rolls back to tip slot if ahead (via deltas), or replays forward by deserializing stored block bodies and applying per-block batches before enabling pruning.

- Phase 3 — Optional Async Apply Mode (DONE)
  - Added `UtxoEventHandlerAsync` using a single-thread ordered executor to offload apply/rollback from the publisher thread while preserving ordering.
  - Guarded by config: `yaci.node.utxo.applyAsync` (default false). Wired in `YaciNode` and exposed in `YaciNodeProducer`.
  - Synchronous reconciliation still runs at startup before handler registration to ensure consistent state on enable.
  - Tests: `UtxoAsyncReconcileTest` covers async ordering behavior and reconciliation (forward replay and rollback) in crash-like scenarios.

- Phase 4 — Planner + MultiGet Prefetch (DONE)
  - Implemented `UtxoProcessor` with per-block `ApplyContext` that prefetches all inputs/collaterals via `multiGet` and exposes a lookup to the writer.
  - Writer uses the context for input lookups in `applyBlock`, reducing JNI crossings and cache pollution.
  - Future: extend to full ApplyPlan/UndoPlan if/when beneficial.

- Phase 5 — Status API + UI (NEXT)
  - Build `/api/v1/status`, optional `/api/v1/status/rocksdb-stats`, and `/ui/status`.

- Phase 6 — Metrics & Prometheus (PENDING)

- Phase 7 — RocksDB Options Tuning (PENDING)
  - DBOptions tuned: pipelined writes, concurrent memtable writes, atomic_flush, parallelism = CPU cores.
  - CFs tuned:
    - utxo_unspent/utxo_spent: ZSTD compression; BlockBasedTable with bloom (~10 bits/key), whole-key filtering, pin L0 filter/index, partitioned filters.
    - utxo_addr: ZSTD; FixedPrefixTransform(28); memtable_prefix_bloom_size_ratio≈0.10; table bloom; whole-key filtering disabled; pin L0 filter/index; partitioned filters.
    - utxo_block_delta: ZSTD compression.
  - Logging: initialization logs reflect effective core options.
- Phase 3 — Optional Async Apply Mode (PENDING)
- Phase 4 — Planner + MultiGet Prefetch (PENDING)
- Phase 5 — RocksDB Options Tuning (PENDING)
- Phase 6 — Format Tweaks (PENDING)
- Phase 7 — Observability & SLOs (PENDING)
- Phase 8 — MMR Backend (PENDING)

---

## API Notes (REST Wire Format)

- Policy: all byte fields exposed by REST are hex-encoded strings.
- Scope: applied at the API boundary only; domain models remain `byte[]` for efficiency.
- Location: node-app DTOs and mapper
  - `node-app/src/main/java/com/bloxbean/cardano/yaci/node/app/api/utxos/dto/UtxoDto.java`
  - `node-app/src/main/java/com/bloxbean/cardano/yaci/node/app/api/utxos/dto/UtxoDtoMapper.java`
  - Resource: `node-app/src/main/java/com/bloxbean/cardano/yaci/node/app/api/utxos/UtxoResource.java`
- Example: `inlineDatum` is serialized as hex (previously base64 by Jackson for `byte[]`).

---

## Status Endpoints & UI

- Endpoints (node-app):
  - `GET /api/v1/status`: returns chain tip, UTXO lastApplied, `lagBlocks`, UTXO store/config, prune cursors (hex), and per‑CF key estimates when RocksDB is active.
  - `GET /api/v1/status/rocksdb-stats?cf=`: on‑demand RocksDB CF stats (heavy). Enabled for ad‑hoc diagnostics; avoid frequent polling.
- UI Route:
  - `GET /ui/status`: lightweight HTML page that polls `/api/v1/status` every 5s and renders:
    - Lag blocks (line chart, last ~50 points)
    - Chain tip and UTXO last‑applied
    - Prune parameters + cursors (hex)
    - CF key estimates (text)
- Configuration:
  - `yaci.node.status.refresh.seconds` (UI polling; client-side default 5s)
  - `yaci.node.metrics.sample.rocksdb.seconds` (if background sampling is introduced later)
