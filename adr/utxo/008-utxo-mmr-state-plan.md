# ADR-008: UTXO MMR State — Design and Rollout Plan

Status: Draft

Authors: yaci team

Date: 2025-09-12

---

## Context

Classic UTXO state provides a practical, queryable view with RocksDB CFs. We want to offer an optional Merkle Mountain Range (MMR) backed commitment to enable lightweight proofs tied to UTXO creation events and to prepare for future accumulator-style features.

An MMR is an append-only accumulator with efficient inclusion proofs. For UTXO, spent removal cannot be represented directly; instead, we support "creation proofs" that show a UTXO was created at a specific leaf. Liveness (still unspent) remains a query to the current state. This approach aligns with Utreexo-style concepts and fits MMR properties.

---

## Goals

- Provide an optional MMR backend selectable via config (`yaci.node.utxo.store=mmr`).
- Keep Classic query semantics (address/outpoint) while adding MMR commitments and proofs for creations.
- Expose a simple programmatic API to obtain proofs (`UtxoMmrState`).
- Keep apply/rollback atomicity and recovery semantics.

Non-goals (initial):
- Full replacement of Classic read path with MMR queries.
- Deletion-aware accumulator (true membership proofs for current unspents).

---

## Data Model and Storage

Column Families:
- `utxo_unspent`, `utxo_addr`, `utxo_spent`, `utxo_block_delta`, `utxo_meta` (existing)
- `utxo_mmr_nodes` (existing CF reserved for MMR; used sparingly initially)

Keys in `utxo_meta` (MMR-related):
- `mmr.leaf.count` → 8-byte BE long (number of leaves appended)
- `mmr.peaks` → 1 byte count N, then N×32-byte peak hashes (bag-of-peaks frontier)
- `mmr.proof:<leafIndex>` → concatenated 32-byte siblings (proof path)
- `mmr.leaf.outpoint:<leafIndex>` → raw outpoint key bytes
- `mmr.idx:<outpointKeyHex>|mmr.idx` → 8-byte BE long leaf index
- `mmr.block.created:<blockNo>` → 4-byte BE int created leaf count in this block (rollback support)

Leaf Hash:
- `H(0x00 || outpointKey || blake2b256(unspentValue))`

Parent Hash:
- `H(0x01 || left || right)`

Bag-of-peaks root:
- Reduce non-null peaks left-to-right with Parent Hash.

---

## Apply and Rollback

Apply (per created output of valid txs):
1. Compute leaf hash from outpointKey and stored unspent value (after Classic writer persists it).
2. Update peaks (carry across equal-height peaks) while collecting sibling(s) used during combines to build the proof path.
3. Increment `mmr.leaf.count` and persist:
   - `mmr.proof:<leafIndex>` → proof path bytes
   - `mmr.leaf.outpoint:<leafIndex>` → outpointKey
   - `mmr.idx:<outpointKeyHex>|mmr.idx` → leafIndex
   - Update `mmr.peaks`
4. Persist `mmr.block.created:<blockNo>` with total created count for rollback.

Rollback:
- Use delegate Classic rollback to revert UTXO changes.
- Trim MMR by iterating `mmr.block.created` for blocks beyond new lastAppliedBlock; for each leaf popped:
  - Delete `mmr.proof:<leafIndex>`, `mmr.leaf.outpoint:<leafIndex>`, and `mmr.idx:<outpointKey>|mmr.idx`.
- Decrement `mmr.leaf.count` accordingly.
- Reset `mmr.peaks` (conservative). A future phase will rebuild peaks from retained nodes.

Recovery:
- Reconciliation aligns Classic first; MMR can be rebuilt from block deltas if peaks are reset. A rebuild routine will be added in a later phase.

---

## API Surface

`UtxoMmrState` (node-api):
- `long getMmrLeafCount()`
- `String getMmrRootHex()`
- `Optional<Long> getLeafIndex(Outpoint)`
- `Optional<MmrProof> getProof(long leafIndex)`

`MmrProof`:
- `leafIndex`, `List<String> pathHex`, `String rootHex`

Verification is client-side.

---

## Phases & Deliverables

Phase 1 — Scaffold + Proofs (DONE)
- MMR backend selectable by config. Delegates Classic for reads/writes; appends leaf per created UTXO; persists per-leaf proof and mappings; rollback trimming by block.
- API added: `UtxoMmrState` + `MmrProof`.

Phase 2 — Rebuild & Peaks Recovery (NEXT)
- Add `rebuildPeaksAndCounts()` from `utxo_block_delta` or retained proof data to recover after crash/trim.
- Optimize storage (compress proofs, limit path length persistence).

Phase 3 — Expose REST endpoints (NEXT)
- `/api/v1/utxo/mmr/root`, `/api/v1/utxo/mmr/proof/{txHash}/{index}`.

Phase 4 — Metrics & Observability (NEXT)
- Expose `mmr.leaf.count`, proof sizes, peaks count, append/rollback timings.

Phase 5 — Advanced Accumulator (FUTURE)
- Explore deletion-aware accumulator / Utreexo-like proofs for current unspents.

---

## Risks & Mitigations

- Storage overhead for per-leaf proofs: compress/limit and/or reconstruct on demand in future.
- Peaks reset on rollback: add rebuild to close correctness gap on restart.
- API changes: Keep additive and optional (MMR backend only).

---

## Acceptance Criteria

- With `yaci.node.utxo.store=mmr`, node applies blocks identically to Classic and maintains MMR state.
- Can fetch a proof for a created outpoint and a root matching current bag-of-peaks.
- Rollback trims MMR entries to remain consistent with Classic state.

