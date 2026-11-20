# Linear Leios spec tracking

Last updated: 2026-11-19 (branch `feat/leios-on-next`, ADR 0016). Network pin last verified: 2026-10-20.

> How to track upstream sources, the release cadence and the re-pin procedure:
> [leios-musashi-source-tracking-guide.md](leios-musashi-source-tracking-guide.md). Per-step record: `adr/leios-todo.md`.

## Current

Yaci targets what Musashi **`rc-2026w42`** runs (ouroboros-leios `49f7c4a2`: cardano-node `2d6a2a66`,
ouroboros-consensus `840e789b`, cardano-ledger `9df9f98c`, ouroboros-network `96ee6e0e`).

| Layer | Pinned to | Where in Yaci |
| :--- | :--- | :--- |
| Ledger CBOR: Dijkstra blocks, headers, transactions, EB references, EB transactions | **CCL `0.8.0-pre8`** ledger-cbor (`gradle/libs.versions.toml`), which reads the w42 format; Dijkstra CDDL at cardano-ledger `9df9f98c` | Yaci maps CCL's views to its models; it has no ledger parser of its own (ADR 0016 D1) |
| Mini-protocols `leios-notify` (18) and `leios-fetch` (19): renumbered w42 tags, `MsgQuit` / `MsgCanceled`, `MsgLeiosBlockTxs = [4, [* #6.24(block_transaction)]]` | Haskell codecs `LeiosDemoOnlyTestNotify.hs` / `LeiosDemoOnlyTestFetch.hs` at ouroboros-consensus `840e789b` | core agents (ADR 0007); payloads are kept as the bytes received (ADR 0016 D2) |
| Complete blocks | n/a (Yaci behaviour) | `BlockSync`, `BlockRangeSync` (ADR 0016 D3 to D5) |

Fixtures for this pin: `core/src/test/resources/leios/w42` (live bytes) and `core/src/test/resources/leios/haskell-w42`
(Haskell golden vectors).

Activation is by era: a Dijkstra block whose header has `leiosCertInBody == true` triggers EB completion. Nothing in
Yaci checks the network magic, and N2N version 15 alone is not treated as a Leios signal. The LeiosFetch side connection
accepts only V15 or later.

There is no runtime protocol-profile switch. Yaci tracks one spec snapshot per release; a format change is a CCL
change first (ADR 0016, Consequences).

## History

| Scope | Pinned to | Status |
| :--- | :--- | :--- |
| Mini-protocols 18/19 + Yaci-side serialization (ADR 0007/0010) | blueprint `leios-prototype` @ `188183b3` (2026-06-29); node releases w25/w26 | mini-protocols kept; serialization superseded by ADR 0016 |
| Block restructure (ADR 0011) | blueprint `leios-prototype` @ `93276ab` sync, head `cb1de23` (2026-07-06); node release `prototype-2026w27` | superseded |
| w40a re-pin (3-item body, `block_transaction`, all Dijkstra CDDL structures) | `prototype-2026w40a`: ouroboros-consensus `cbd10033`, cardano-ledger `d93c6546` | superseded on 2026-10-20 |
| w42 re-pin on the `next-leios` stack (Yaci-side parsers) | `rc-2026w42` | replaced by CCL parsing on `feat/leios-on-next` (ADR 0016) |
