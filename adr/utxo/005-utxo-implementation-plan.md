# UTXO State — Multi‑Phase Implementation Plan

Status: Phase 0–2 completed; Phase 3 complete (classic store apply/rollback/prune/query); next: Phase 4
Owners: Yaci Node Team
Scope: node-api, node-runtime, node-app, events-core, events-processor

## Overview

This plan operationalizes ADR-004 (UTXO State — Design and Plan). It delivers a pluggable UTXO subsystem with Classic and MMR variants, atomic per‑block updates, rollback safety, configurable pruning, and fast address/credential queries. Work proceeds in phases with clear deliverables, gating checks, and test criteria.

Key principles
- Single RocksDB instance shared with ChainState; new CFs for UTXO.
- Event-driven updates via BlockAppliedEvent and RollbackEvent.
- Compact Delta (default) to minimize duplication; Self‑Contained Delta optional.
- Two indexes enabled by default: address_hash and payment_credential.
- Deterministic CBOR value encoding; REST JSON API mapping.

Dependencies
- Events pipeline available in node-runtime (events-core/processor).
- DirectRocksDBChainState provides DB access and lifecycle.

Risks & Mitigations
- Partial updates: use WriteBatch and atomic_flush.
- Long rollbacks/pruning race: gate with rollbackWindow ≥ pruneDepth; batch deletes.
- Performance regressions: prefix bloom, whole-key bloom, pipelined writes; measure and iterate.

---

## Phase 0 — Project Bootstrap & Guardrails

Deliverables
- Feature flag plumbing with sane defaults; config keys under `yaci.node.utxo.*`.
- No-op wiring when disabled; build still green.

Tasks
- Add options to `node-api`/runtime config records:
  - `yaci.node.utxo.enabled=true`
  - `yaci.node.utxo.store=classic|mmr` (default classic)
  - `yaci.node.utxo.pruneDepth=2160`, `rollbackWindow=4320`, `pruneBatchSize=500`
  - `yaci.node.utxo.index.address_hash=true`, `payment_credential=true`
  - `yaci.node.utxo.indexingStrategy=both` (helper), `delta.selfContained=false`
- Load options in `node-app` (Quarkus) from `application.yml`.
- Wire construction path in `YaciNode` with lazy UTXO init and guard by `enabled`.

Tests
- Unit: config parsing defaults/overrides.
- Smoke: node runs with UTXO disabled and with UTXO enabled but no listeners yet.

Exit criteria
- Flags available; disabled mode is a true no-op.

Status (done)
- Added `yaci.node.utxo.*` flags to Quarkus producer and plumbed into `RuntimeOptions.globals` as no-op config.
- No UTXO listeners or DB usage yet when enabled.

---

## Phase 1 — API & Types (node-api)

Deliverables
- `UtxoState` interface and related types (records for Utxo, Outpoint, Value summary if needed).
- Minimal query API: `getUtxosByAddress`, `getUtxo`, optional `getBalance`.
- Store selector enum: `UtxoStoreType { CLASSIC, MMR }`.

Tasks
- Define interfaces and DTOs with Java 21 records; align names with ADR.
- Map bech32/hex address inputs; decode utilities in helper module if shared.

Tests
- Unit: argument validation, simple in-memory fake implementation (for early tests).

Exit criteria
- API stable and published; no coupling to RocksDB.

Status (done)
- Added `UtxoState`, `UtxoStoreType`, and DTOs (`Outpoint`, `AssetAmount`, `Utxo`) under `node-api`.
- Kept surface minimal and storage-agnostic; in-memory fake deferred to later phase if needed.

---

## Phase 2 — RocksDB Integration & CF Provisioning

Deliverables
- Extend `DirectRocksDBChainState` (or a companion provider) to register UTXO CFs:
  - `utxo_unspent`, `utxo_spent`, `utxo_addr`, `utxo_block_delta`, `utxo_meta`, `utxo_mmr_nodes`.
- Accessors to obtain `RocksDB` and CF handles for UTXO module (encapsulated provider).

Tasks
- Add CF descriptors; ensure `atomic_flush=true`, compression=zstd, prefix extractors per CF.
- Implement provider class in node-runtime to pass handles to UTXO store(s); respect lifecycle close.

Tests
- Unit: CF existence, write/read sanity for each CF.
- Build: ensure ChainState boot still works on fresh DB and existing DB.

Exit criteria
- DB holds new CFs; no conflicts with existing ChainState usage.

Status (done)
- Added UTXO CF descriptors to `DirectRocksDBChainState` and created a name→handle registry.
- Introduced `RocksDbSupplier` and `RocksDbContext` to expose DB and CF handles without adding UTXO logic to chainstate.
- Added `UtxoCfNames` constants for CF names.

---

## Phase 3 — ClassicUtxoStore (Apply, Rollback, Prune)

Deliverables
- Classic UTXO store implementing `UtxoState`, listening to events, with atomic per‑block updates.
- Compact Delta (default) in `utxo_block_delta`; `utxo_spent` stores pre‑spend bodies.
- Valid/invalid tx semantics including collateralReturn handling.

Tasks
- Event listeners
  - Subscribe to `BlockAppliedEvent`: parse txs; build mutations (inputs/outputs) and delta (created/spent outpoints).
  - Subscribe to `RollbackEvent`: iterate blockNumber down to target; invert deltas.
- Apply logic (WriteBatch)
  - Insert new unspents into `utxo_unspent` (CBOR UtxoRecord) and `utxo_addr` indexes (address_hash, payCred).
  - On spend: write `utxo_spent` value (CBOR SpentRecord with pre-spend UtxoRecord); delete `utxo_unspent` and index markers.
  - Write `utxo_block_delta[block]=delta` (compact). Honor `delta.selfContained` to embed bodies if enabled.
- Rollback logic
  - For each delta: delete created unspents (+indexes); restore each spent from `utxo_spent` (compact) or from delta (self-contained); remove delta key.
- Pruning job (lightweight, bounded per block)
  - Spent: delete entries older than `pruneDepth` (by block/slot) and their blob if any auxiliary exists.
  - Deltas: delete entries older than `rollbackWindow`.
- Address/credential queries
  - Prefix scans over `utxo_addr`; fetch UtxoRecords via MultiGet on `utxo_unspent`.
  - Deterministic pagination using composite key ordering.
- Serialization
  - CBOR encoders/decoders for UtxoRecord, SpentRecord, BlockDelta (integer-keyed, canonical CBOR).

Tests
- Unit: apply single block (valid and invalid txs), rollback to N blocks, prune behavior.
- Integration: sync a range, verify balances for known addresses, execute rollbacks, verify invariants.

Exit criteria
- Classic store functionally complete with apply/rollback/prune and queries.

Status (done)
- Implemented `ClassicUtxoStore` with per-block apply, rollback via compact deltas, bounded pruning, and basic queries.
- Wired initialization in `YaciNode` when `yaci.node.utxo.enabled=true`, `store=classic`, and RocksDB is used.
- Compact delta only (self-contained mode not yet implemented).
- Address indexes implemented for both `address_hash` and `payment_credential` (same CF with distinct 28-byte prefixes).
- Spent retention window is enforced to be ≥ `rollbackWindow` to guarantee compact-delta rollbacks.

---

## Phase 4 — REST API (node-app)

Deliverables
- Endpoints
  - `GET /addresses/{addr}/utxos?cursor=&limit=`
  - `GET /utxos/{txHash}/{index}`
  - Optional: `GET /addresses/{addr}/balance`
- JSON mapping (snake_case) aligned with yaci-store clients.

Tasks
- Wire `UtxoState` bean via runtime options; return 503 or empty if disabled.
- Implement pagination cursors from index keys.
- Validation and error responses.

Tests
- Quarkus tests: endpoint contract, pagination, 404 cases.

Exit criteria
- Endpoints stable; basic performance verified.

---

## Phase 5 — MMR Variant

Deliverables
- `MmrUtxoStore` implementing same `UtxoState` queries and event listeners.
- `utxo_mmr_nodes` storage for nodes/peaks; per-block rewind data captured in `utxo_block_delta.mmr`.

Tasks
- Leaf commitment format (hash of outpoint||addr||value digest) and node encoding.
- Append on apply; record inserted positions and peaks/root in delta.
- Rewind on rollback using per-block mmr delta.
- Share `utxo_addr` and `utxo_unspent` for queries (MMR as integrity layer).

Tests
- Unit: MMR append/rewind invariants, root consistency across rollbacks.
- Integration: parity with Classic store queries; optional root exposure endpoint.

Exit criteria
- MMR store operational; selectable via config.

---

## Phase 6 — Performance, Tuning, and Observability

Deliverables
- Metrics and logs for core operations; RocksDB options tuned.

Tasks
- Metrics: counters (created/spent/pruned), gauges (unspent count, index sizes), timers (apply/rollback/prune).
- Logs: block slot/number, counts per block, prune stats; debug assertions optional.
- Tuning: apply guidance from ADR (prefix extractor, bloom, pipelined writes, atomic_flush, zstd).
- Optimize: MultiGet for outpoint fetch after scans; batch sizes for prune/rollback.

Tests
- Bench harness for address scan and outpoint lookup throughput (dev profile).

Exit criteria
- Meets target throughput/latency for typical workloads; no hot spots.

---

## Phase 7 — Hardening & Edge Cases

Deliverables
- Robust handling of Byron/EBB boundaries, pointer addresses (if needed), datum/scriptRef corner cases.

Tasks
- Confirm tx validity/era detection; collateralReturn index placement.
- Optional pointer-address post-processing (pre-Conway) if exposed in API.
- Large rollback simulation; delta/idempotency checks.

Tests
- Edge vectors (invalid txs with/without collateralReturn, large MA sets, heavy datum/scriptRef payloads).

Exit criteria
- Edge cases covered; no data corruption on adversarial sequences.

---

## Phase 8 — Documentation & Migration

Deliverables
- User docs: enabling UTXO state, tuning, API usage, pruning/rollback semantics.
- Operator notes: storage growth estimates, compaction guidance.

Tasks
- Update README, docs/, and adr/ with final switches and examples.
- Add migration notes for existing nodes (CF creation on startup).

Exit criteria
- Docs published; flags stable.

---

## Backlog / Nice‑to‑Have

- Optional balance aggregates per address/credential for faster summaries.
- UTxORPC/gRPC adapter (future): map internal CBOR to UTxORPC types.
- Proof endpoints for MMR (if exposed).
- Background compaction triggers post-prune/rollback waves.

---

## Acceptance Checklist (Roll‑up)

- Flags and CFs provisioned; disabled mode is no-op.
- Classic store: apply/rollback/prune; valid/invalid tx semantics; address and outpoint queries correct.
- MMR store: append/rewind; root stable across rollbacks; queries parity with Classic.
- REST endpoints live; pagination deterministic; JSON mapping stable.
- Metrics and logs in place; performance meets targets; no partial updates.
- Pruning operates within configured budgets; rollbackWindow ≥ pruneDepth enforced.
- Documentation complete.
