# ADR-007: UTXO Observability and Status — Data Points and Derivations

Status: Draft

Date: 2025-09-12

---

## Overview

This document catalogs the status fields and runtime metrics exposed by the UTXO subsystem and the node status page, and briefly explains how each data point is derived. The goal is to make behavior transparent and provide a stable basis for dashboards, alerts, and troubleshooting.

These data points are backend‑agnostic; Classic and future MMR stores should expose the same surface (via `UtxoStatusProvider`), with backend‑specific internals hidden behind the writer.

---

## Status API Fields (`GET /api/v1/status`)

- chain.slot: current local tip slot from `ChainState.getTip()`.
- chain.blockNumber: current local tip block number from `ChainState.getTip()`.
- chain.blockHash: hex-encoded tip block hash from `ChainState.getTip()`.
- utxo.enabled: true when UTXO store is initialized and active.
- utxo.store: store type, e.g., `classic` (provided by `UtxoStatusProvider.storeType()`).
- utxo.lastAppliedBlock: last applied UTXO block number, from `utxo_meta.meta.last_applied_block` (8‑byte big‑endian long).
- utxo.lastAppliedSlot: last applied slot, from `utxo_meta.meta.last_applied_slot` (8‑byte big‑endian long).
- utxo.lagBlocks: derived as `chain.blockNumber - utxo.lastAppliedBlock`, lower-bounded at 0.
- utxo.prune.pruneDepth: configured `yaci.node.utxo.pruneDepth` (default 2160).
- utxo.prune.rollbackWindow: configured `yaci.node.utxo.rollbackWindow` (default 4320).
- utxo.prune.pruneBatchSize: configured `yaci.node.utxo.pruneBatchSize` (default 500).
- utxo.prune.deltaCursorKey: hex of `utxo_meta.prune.delta.cursor` (last processed delta key) or null.
- utxo.prune.spentCursorKey: hex of `utxo_meta.prune.spent.cursor` (last processed spent key) or null.
- utxo.metrics: rolling runtime metrics map (see below).
- utxo.cfEstimates: sampled CF key estimates (see below) — when metrics sampling is enabled.

---

## Runtime Metrics (Classic implementation)

- apply.ms.avg: rolling average (ms) of per‑block apply time.
  - Measured around `applyBlock` with `System.nanoTime()`; stored in a deque (window ≈ 200 entries).
- apply.ms.p95: rolling 95th percentile (ms) over the same deque window.
- apply.created.last: number of outputs created in the most recent applied block (size of `createdRefs`).
- apply.spent.last: number of inputs spent in the most recent applied block (size of `spentRefs`).
- throughput.blocksPerSec: approximate block throughput over the last 30 seconds.
  - We record a timestamp for each applied block; throughput is `count(last_30s) / 30.0`.
- prune.ms.last: duration (ms) of the most recent `pruneOnce()` tick, measured with `System.nanoTime()`.
- prune.deltaDeleted.last: number of `utxo_block_delta` keys deleted in the most recent prune tick.
- prune.spentDeleted.last: number of `utxo_spent` keys deleted in the most recent prune tick.
- block.size.last: last applied block body size (bytes), from `Block.header.headerBody.blockBodySize`.
- block.size.avg: rolling average (bytes) of block body sizes over a bounded window (≈ 200 entries).

Notes:
- Metrics collection is guarded by `yaci.node.metrics.enabled` (default true). When disabled, `getMetrics()` returns an empty map and no sampling threads are spawned.
- Rolling windows are in‑memory only; they reset on restart.

---

## CF Estimates (sampled)

- utxo_unspent.estimateNumKeys
- utxo_spent.estimateNumKeys
- utxo_addr.estimateNumKeys
- utxo_block_delta.estimateNumKeys

Derivation: Each value is sampled from RocksDB via `db.getProperty(cf, "rocksdb.estimate-num-keys")`. Sampling runs on a virtual‑thread scheduler at a configurable interval and publishes an immutable snapshot for the status API.

- Sampling interval: `yaci.node.metrics.sample.rocksdb.seconds` (default 30; set 0 to disable).
- Returned via `UtxoStatusProvider.getCfEstimates()`; status API simply forwards the snapshot.

Rationale: `estimate-num-keys` is inexpensive compared to `cfstats` and safe to poll periodically. Heavy CF stats remain out of band.

---

## Pruning — Cutoffs and Cursors

- Delta cutoff: `currentSlot - rollbackWindow`.
  - Deltas with `slot <= cutoff` are eligible for deletion.
- Spent cutoff: `currentSlot - max(pruneDepth, rollbackWindow)`.
  - Spent entries with `spentSlot <= cutoff` are eligible for deletion.
- Cursors:
  - `prune.delta.cursor`: last processed key in `utxo_block_delta`; next tick seeks to this position and continues.
  - `prune.spent.cursor`: last processed key in `utxo_spent`; next tick resumes there and wraps once per pass if necessary.
- Safety: `rollbackWindow >= pruneDepth` ensures all rollback data remains available within the rollback horizon. Reconciliation executes before the prune scheduler starts at startup.

---

## Reconciliation — Startup Behavior

- Reads `meta.last_applied_block/slot` and compares with `ChainState.getTip()`.
- If ahead: roll back to tip slot by walking `utxo_block_delta` backward within the rollback window (using the same batch semantics as runtime rollback).
- If behind: forward replay missing blocks by reading bodies from `ChainState.getBlockByNumber(n)` and calling `applyBlock` per block.
- All operations are idempotent; missing unspents or duplicate puts are handled safely.

---

## Event Ordering and Atomicity

- Handler: `UtxoEventHandler` is synchronous by default; events are applied on the publisher thread in order.
- Atomicity: Each block’s UTXO changes are committed via a single `RocksDB.WriteBatch` across CFs; WAL is enabled by default.
- High‑water marks: `meta.last_applied_block/slot` are updated in the same batch, ensuring crash‑consistent cutoffs and reconciliation.
- Pruning: Runs outside the hot path on a virtual‑thread scheduler; bounded deletions per tick; progress persisted via cursors.

---

## Configuration Summary

- yaci.node.utxo.enabled: enable/disable UTXO store.
- yaci.node.utxo.store: `classic` (default) or future backends.
- yaci.node.utxo.pruneDepth: default 2160.
- yaci.node.utxo.rollbackWindow: default 4320.
- yaci.node.utxo.pruneBatchSize: default 500.
- yaci.node.utxo.prune.schedule.seconds: scheduler period for prune service (default 5s).
- yaci.node.utxo.metrics.lag.logSeconds: lag logging interval in `YaciNode` (default 10s).
- yaci.node.utxo.lag.failIfAbove: optional threshold to warn/fail fast when lag exceeds N blocks (default disabled).
- yaci.node.metrics.enabled: global metrics switch (default true).
- yaci.node.metrics.sample.rocksdb.seconds: CF estimate sampling interval (default 30; 0 disables sampling).
- yaci.node.status.refresh.seconds: UI polling interval (client-side default 5s; configurable in future).

---

## UI Notes (`/ui/status`)

- Renders a consolidated JSON from `/api/v1/status` and shows:
  - Tip and UTXO last‑applied info; lag chart (last 50 points).
  - Apply latency (avg/p95), created/spent counts; throughput (blocks/sec).
  - Prune parameters and cursors; CF estimate snapshot.
  - Block size (last/avg).
- Visuals are implemented with Tailwind (CDN) and a subtle three.js background; animation honors `prefers-reduced-motion` and `?noanim=1`.

---

## Future Extensions

- Granular metric toggles and Prometheus/Micrometer export.
- Heavy RocksDB `cfstats` on-demand endpoint with guardrails.
- MMR backend status parity (same surface via `UtxoStatusProvider`).
- Additional panels (header/body throughput, network stats) as needed.

