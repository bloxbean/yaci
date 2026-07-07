# Linear Leios Spec Tracking

Last verified: 2026-07-07

> How to track upstream sources, release cadence, and the re-pin procedure:
> see `docs/leios/leios-musashi-source-tracking-guide.md`.

## Pin matrix

| Scope | Pinned to | Status |
| :--- | :--- | :--- |
| Mini-protocols 18/19 + serialization/listener layer (ADR 0007/0010) | blueprint `leios-prototype` @ `188183b3` (2026-06-29); node releases w25/w26 | implemented |
| Block restructure re-pin (ADR 0011) | blueprint `leios-prototype` @ `93276ab` sync, head `cb1de23` (2026-07-06); node release **`prototype-2026w27`** | implemented |

Yaci's experimental Linear Leios support currently implements the Musashi network mini-protocol wire format from
the cardano-blueprint `leios-prototype` branch at commit `188183b37081fa012fa890236edb7771f96ae92f`, plus the
Dijkstra ranking-block restructure from the `prototype-2026w27` re-pin (`93276ab` ledger CDDL sync).

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
