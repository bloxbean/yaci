# UTXO MMR Backend — Client Usage and Value

Status: Reference

Last updated: 2025-09-13

Related: ADR-008 (UTXO MMR State — Design and Rollout Plan)

---

## Overview

Yaci Node provides an optional Merkle Mountain Range (MMR) backend for UTXO creation commitments. When enabled, the node continues to serve Classic UTXO queries while maintaining an append-only accumulator that supports compact “creation proofs” for individual UTXOs.

- Enable via config: `yaci.node.utxo.store=mmr`
- Core idea: Every created UTXO appends one MMR leaf. The node stores per-leaf proofs, a root commitment (bag-of-peaks), and a mapping from outpoint → leaf index.
- Reads remain unchanged; MMR data augments the system with verifiable commitments.

Hashing scheme:
- Leaf: `H(0x00 || outpointKey || blake2b256(unspentValueCBOR))`
- Parent: `H(0x01 || left || right)`
- Root: bag-of-peaks reduction of non-null peaks left→right

Where `outpointKey = txHash (32B) || index (uint16 BE)` and `H = blake2b256`.

---

## What the Node Persists (MMR)

In the RocksDB `utxo_meta` column family:
- `mmr.leaf.count` → total appended leaves (8-byte BE)
- `mmr.peaks` → 1 byte count N, followed by N×32-byte peak hashes
- `mmr.proof:<leafIndex>` → concatenated 32-byte siblings (leaf→peak path)
- `mmr.leaf.outpoint:<leafIndex>` → raw `outpointKey`
- `hex(outpointKey)|mmr.idx` → 8-byte BE `leafIndex`
- `mmr.block.created:<blockNo>` → number of leaves created in that block (rollback trimming)

Rollback trims leaves created after the new tip, deletes corresponding proof/mappings, decrements `mmr.leaf.count`, and resets `mmr.peaks` (later rebuilt on reconcile).

---

## REST Endpoints (when MMR is enabled)

1) `GET /api/v1/utxo/mmr/root`
- Returns current commitment and size.
- Example response:

```json
{
  "root": "<hex32>",
  "leafCount": 1234567
}
```

2) `GET /api/v1/utxo/mmr/leaf-index/{txHash}/{index}`
- Resolves an outpoint to its MMR leaf index.
- Example response:

```json
{
  "leafIndex": 987654
}
```

3) `GET /api/v1/utxo/mmr/proof/{txHash}/{index}`
- Returns a creation proof for the given outpoint.
- Example response:

```json
{
  "leafIndex": 987654,
  "pathHex": ["<hex32>", "<hex32>", "..."],
  "rootHex": "<hex32>"
}
```

Status endpoint also includes MMR when enabled:
- `GET /api/v1/status` → `utxo.mmr.root`, `utxo.mmr.leafCount` within the `utxo` section.

---

## Client Verification Workflow

1) Fetch proof
- Call `/api/v1/utxo/mmr/proof/{txHash}/{index}` → obtain `leafIndex`, `pathHex`, `rootHex`.

2) Compute leaf hash
- Construct `outpointKey = txHash || uint16_be(index)`.
- Reconstruct `unspentValueCBOR` exactly as stored by the node (use the same codec if possible).
- Compute `leaf = blake2b256(0x00 || outpointKey || blake2b256(unspentValueCBOR))`.

3) Fold the path to the peak
- Starting with `carry = leaf`, iteratively apply `carry = blake2b256(0x01 || sibling || carry)` for each `sibling` in `pathHex`.

4) Anchor to a root commitment
- The node returns `rootHex` for convenience. For trust-minimized verification, compare against a root commitment obtained from an independent or signed source (e.g., checkpointed feed, another trusted node). If you trust this node for roots, `rootHex` anchors the proof to its current snapshot.

Notes:
- `pathHex` is a leaf-to-peak path. Full bag-of-peaks requires all peaks; we include `rootHex` so clients can anchor proofs to a specific commitment without fetching the frontier.
- On rollback, leaves from reverted blocks are pruned; proofs for those leaves will cease to exist or no longer resolve from `/leaf-index`.

---

## Concrete Use Cases

- Lightweight wallets (trust-but-verify creation)
  - Upon receiving a deposit, fetch `/mmr/proof/{txHash}/{index}` and store the proof + `rootHex`. Later verify locally that the UTXO was created, without maintaining full history. For stronger guarantees, verify against a signed/independent `rootHex`.

- Exchanges and custody (attached receipts)
  - Persist MMR creation proofs with deposit records. Auditors can verify deposits against checkpointed `rootHex` values without re-indexing chain history.

- Indexer cross-checks and monitoring
  - Periodically compare `/mmr/root` across nodes/indexers. Divergence indicates desync, missed rollback, or data drift. Combine with `/status` lag metrics for alerts.

- API gateways / bridges (light clients)
  - Provide `/mmr/proof` to external consumers who maintain their own trusted roots. Enables compact verification with minimal bandwidth.

- Archival checkpoints and reproducible rebuilds
  - Store `(leafCount, rootHex)` at operational checkpoints. Future database rebuilds can be validated against these commitments, detecting silent corruption.

- Rollback-aware proof hygiene
  - Since reverted leaves are trimmed, stale proofs become invalid or unresolvable. Systems should refresh proofs if a rollback crosses their proof’s block range.

---

## Advantages of the MMR Backend

- Verifiable creation proofs: O(log n) paths; compact and append-only.
- Low operational overhead: Coexists with Classic store; no change to read semantics.
- Rollback safety: Trimming by block keeps MMR in sync with chain state; peaks rebuilt on reconcile when necessary.
- Auditability: Simple root commitment via `/mmr/root` suitable for dashboards, attestation, and alerting.
- Future-ready: Provides the base for advanced accumulator features (e.g., deletion-aware proofs) without breaking current API consumers.

---

## Practical Examples

- Current root and leaf count:

```bash
curl -s http://localhost:8080/api/v1/utxo/mmr/root
```

- Resolve outpoint to leaf index:

```bash
curl -s http://localhost:8080/api/v1/utxo/mmr/leaf-index/<txHash>/<index>
```

- Fetch a creation proof:

```bash
curl -s http://localhost:8080/api/v1/utxo/mmr/proof/<txHash>/<index>
```

- Node status (includes MMR when enabled):

```bash
curl -s http://localhost:8080/api/v1/status
```

---

## Operational Notes

- Enable MMR with `yaci.node.utxo.store=mmr` (e.g., `node-app/src/main/resources/application.yml` or `application.properties`).
- Pair proofs with a snapshot: store `rootHex` (and optionally `leafCount`) from the same request for reproducibility.
- Trust model: For trust-minimized verification, compare proofs against a root commitment obtained from a trusted/independent source.
- Encoding: Use the same UTXO CBOR codec as the node to reproduce `unspentValueCBOR` byte-for-byte; mixing codecs can lead to hash mismatches.
- Metrics: `/api/v1/status` exposes `utxo.mmr.root` and `utxo.mmr.leafCount`, plus general RocksDB/UTXO metrics.

---

## Future Enhancements (Planned)

- Peaks/frontier rebuild: Stable recovery after crashes/rollbacks without full replay.
- Proof storage optimization: Compression or reconstruction-on-demand to reduce storage overhead.
- Extended surfaces: Optional endpoint to expose current peaks/frontier for independent bagging.
- Advanced accumulators: Explore deletion-aware or Utreexo-like proofs for current unspents.

