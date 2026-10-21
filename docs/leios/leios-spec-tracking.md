# Linear Leios Spec Tracking

Last verified: 2026-10-20 (Musashi `rc-2026w42`)

> How to track upstream sources, release cadence, and the re-pin procedure:
> see `docs/leios/leios-musashi-source-tracking-guide.md`.

## Pin matrix

| Scope | Pinned to | Status |
| :--- | :--- | :--- |
| Mini-protocols 18/19 + serialization/listener layer (ADR 0007/0010) | blueprint `leios-prototype` @ `188183b3` (2026-06-29); node releases w25/w26 | implemented |
| Block restructure re-pin (ADR 0011) | blueprint `leios-prototype` @ `93276ab` sync, head `cb1de23` (2026-07-06); node release **`prototype-2026w27`** | superseded |
| w40a re-pin (3-item body, `block_transaction`, all Dijkstra CDDL structures) | node release `prototype-2026w40a`: ouroboros-consensus `cbd10033`, cardano-ledger `d93c6546` (= blueprint `dijkstra.cddl` `131cc95a`) | superseded on 2026-10-20 |
| **w42 re-pin (current)**: renumbered LeiosNotify/LeiosFetch with `MsgQuit`/`MsgCanceled`, `MsgLeiosBlockTxs = [4, [* #6.24(block_transaction)]]`, witness key 8 (Plutus V4), parameter update keys 49–56 | node release **`rc-2026w42`** (ouroboros-leios `49f7c4a2`): cardano-node `2d6a2a66`, ouroboros-consensus `840e789b`, cardano-ledger `9df9f98c`, ouroboros-network `96ee6e0e` | implemented, live-tested |

Yaci's experimental Linear Leios support currently implements what Musashi `rc-2026w42` runs. The pins are the
Haskell codecs the release builds (`LeiosDemoOnlyTestNotify.hs` / `LeiosDemoOnlyTestFetch.hs` at ouroboros-consensus
`840e789b`) and the Dijkstra CDDL at cardano-ledger `9df9f98c`; cardano-blueprint `main` @ `0460c5a6` matches them
except that the ledger CDDL also defines an unused `eb_references` array. The per-step record is in
`adr/leios-todo.md`. The paragraphs below describe the original w25–w27 implementation.

The implemented scope is limited to node-to-node mini-protocol framing for:

- `leios-notify` on mux protocol id `18`
- `leios-fetch` on mux protocol id `19`

Endorser block, vote, and transaction-list bodies are decoded best-effort while keeping raw CBOR. Dijkstra
ranking blocks are parsed through the w27 nested block-body layout.

The source CDDL files for the current implementation are:

- `src/network/node-to-node/leios-notify/messages.cddl`
- `src/network/node-to-node/leios-fetch/messages.cddl`

This support is Musashi-specific and uses network magic `164`. N2N version `15` is required by the prototype
handshake, but version `15` alone is not treated as a Leios capability signal. Leios activation must remain
explicitly tied to Musashi, or to a future handshake/network capability once one exists.

There is no runtime `LeiosProtocolProfile` enum. While the protocol is still moving, Yaci tracks one spec snapshot
per release. If two live networks later speak different Leios dialects, prefer lenient decoding and explicit
network/capability flags over threading profile arguments through every serializer.

Future conformance work should vendor the two CDDL files above into test resources with this commit hash and add
round-trip checks for each supported message shape.
