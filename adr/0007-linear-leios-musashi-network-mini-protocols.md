# ADR 0007: Linear Leios (Musashi) Network Mini-Protocols — Transport-Only (Consolidated)

Date: 2026-07-01

Status: Proposed 


## Context

**Linear Leios (CIP-0164)** extends Ouroboros Praos with an endorsement layer
(Endorser Blocks + committee votes + BLS certificates) to raise throughput while
preserving Praos security. A prototype testnet — **"Musashi"** — is live (network
magic **164**, Dijkstra era / PV12). It adds **two new node-to-node (N2N)
mini-protocols** on the existing Ouroboros mux:

- **`LeiosNotify`** (protocol **18**) — pull-based dissemination: a peer announces
  new Endorser Blocks (EBs), offers EB bodies / their transaction closures for
  eager fetching, and diffuses votes.
- **`LeiosFetch`** (protocol **19**) — pull-based fetch: the client requests a full
  EB, a bitmap-selected subset of an EB's transactions, (in the full spec) votes,
  or a slot range of EBs; the server streams the response.

Ranking Blocks (the Praos-chain blocks that will carry the Leios certificate)
continue to flow over the **existing** ChainSync / BlockFetch protocols and are
**out of scope** here.

### Why a transport-only PR, and why two profiles

The blueprint marks the heavy Leios structures as not-yet-final (`endorser_block`,
`announcement_header`, `bitmaps`, `tx`, and vote fields are `any` / `TODO` /
`REVIEW`). The **framing** of the mini-protocols (states, agencies, message tags,
request/response arity) is stable enough to implement, while the **contents** of
the messages are still moving. This PR cuts exactly at that boundary: implement
the two state machines and message envelopes; carry every payload as an
**opaque CBOR value** (`LeiosRawCbor` / `byte[]`); defer all block/tx/vote/cert
decoding and BLS to a later PR.

Critically, the wire that Musashi speaks **today** differs from the current
Blueprint `main` in several envelope details. Rather than guess, this ADR pins
**two profiles** against **exact commits** and implements the prototype first:

- **`MUSASHI_PROTOTYPE`** — matches what the testnet exercises now; the initial,
  default, feature-gated target.
- **`BLUEPRINT_CURRENT`** — the fuller `main` shape; a follow-up profile, inactive
  until a peer supports it.

### Sources reviewed

- **cardano-blueprint** (`cardano-scaling/cardano-blueprint`), the network spec of
  record:
  - `BLUEPRINT_CURRENT` = `main` @ **`e773b951a0697ac1a7eeb755014ddba4c1856a05`**
  - `MUSASHI_PROTOTYPE` = `leios-prototype` @ **`188183b37081fa012fa890236edb7771f96ae92f`**
  - Paths (both branches): `src/network/node-to-node/leios-notify/{README.md,messages.cddl}`,
    `src/network/node-to-node/leios-fetch/{README.md,messages.cddl}`,
    `src/network/node-to-node/handshake/README.md`,
    `src/codecs/base.cddl` (`slotNo = word64`, `hash = bstr .size 32`,
    `word16 = uint .size 2`, `word32 = uint .size 4`)
- **CIP-0164** (Leios) — referenced by the blueprint pages for rationale.
- **Yaci codebase** — existing mini-protocol scaffolding this PR mirrors
  (`core/.../protocol/{peersharing,appmsg}/**`, `Agent`/`State`/`Serializer`,
  `handshake/util/N2NVersionTableConstant.java`, helper `*Sync*` fetchers).

## Decision

Add Yaci core support for the two Leios N2N mini-protocols as a **feature-gated,
profile-aware, transport-only** implementation:

- `LeiosNotify` = mini-protocol **18**, `LeiosFetch` = mini-protocol **19**.
- Default target = `MUSASHI_PROTOTYPE`; structure the code so `BLUEPRINT_CURRENT`
  can be added without touching ChainSync / BlockFetch / Store.
- Keep **all** payload-bearing values opaque (`LeiosRawCbor` / `byte[]` CBOR):
  announcements, Endorser Blocks, transaction lists, votes, certificates, vote
  deliveries.
- The network layer may parse **envelope tags, points, `(slot, voter_id)`
  selectors, slot ranges, envelope size scalars (`eb_size`), and the bitmap map**
  — nothing else. It must not decode transactions, blocks, EB maps, votes, BLS
  signatures, or ledger structures.
- Opaque means "no domain decoding". It does not require byte-for-byte
  preservation of inbound CBOR encodings through Yaci's existing `DataItem`
  parser/re-serializer unless the implementation adds a dedicated
  byte-preserving extraction path.

### Non-goals (this PR does NOT do)

Dijkstra block/header/body decoding; transaction CBOR deserialization; EB-map →
transaction-reference decoding; vote/certificate decoding; BLS verification;
Ledger or Yaci-Store persistence changes; EB-closure validation; request
scheduling beyond a small safe queue; full catch-up/range support unless the
target profile and peer already support it. Those are follow-up PRs, once the
block/ledger formats are pinned.

## Protocol Baseline

Standard Ouroboros framing applies: each mini-protocol is a replicated state
machine multiplexed over the shared connection; messages are CBOR arrays whose
first element is an integer tag; **agency** decides who sends next. Yaci already
implements the mux, the `0x8000` responder flag, and >64 KiB payload
segmentation — **no mux changes are needed**. Protocol ids 18/19 are within Yaci's
valid mux range (`Agent` warns only above 199) and do not collide with Yaci's
app-layer squatters (mini-protocol ids 100/101/102).

### LeiosNotify (protocol 18)

State machine (identical in both profiles): `StIdle` (client agency) →
`MsgLeiosNotificationRequestNext` → `StBusy` (server agency) → one
announcement/offer → `StIdle`; `MsgClientDone` from `StIdle` → `StDone`.

| State  | Agency | 
| :----- | :----- |
| StIdle | Initiator (client) |
| StBusy | Responder (server) |
| StDone | terminal |

**`MUSASHI_PROTOTYPE` (188183b):**

```cddl
msgLeiosNotificationRequestNext = [0]
msgLeiosBlockAnnouncement       = [1, announcement]        ; announcement = any (opaque)
msgLeiosBlockOffer              = [2, point, eb_size]       ; eb_size = word32
msgLeiosBlockTxsOffer           = [3, point]
msgLeiosVotes                   = [4, [1* vote]]            ; inline opaque votes
msgClientDone                   = [5]
point = [slot, eb_hash]                                    ; explicit array
vote  = [slot, eb_hash, voter_id: word16, vote_signature: bytes .size 48]
```

**`BLUEPRINT_CURRENT` (e773b95):**

```cddl
msgLeiosNotificationRequestNext = [0]
msgLeiosBlockAnnouncement       = [1, announcement_header]  ; = any (opaque)
msgLeiosBlockOffer              = [2, point]                ; (README table shows "point, size")
msgLeiosBlockTxsOffer           = [3, point]
msgLeiosVotesOffer              = [4, [1* (slot, voter_id)]]; vote *identifiers*, not bodies
msgClientDone                   = [5]
point    = (slot, eb_hash)                                 ; CDDL group
voter_id = word16                                          ; REVIEW: size
```

Prototype → current differences: (a) prototype `BlockOffer` carries **`eb_size`**;
`main`'s CDDL body omits it (its README table shows `point, size`, i.e. `main` is
internally inconsistent and the prototype's `[2, point, eb_size]` is the concrete
live form). (b) Prototype **tag 4 = inline vote bodies** (`MsgLeiosVotes`); `main`
tag 4 = **vote identifiers** (`MsgLeiosVotesOffer`, `(slot, voter_id)` pairs).
(c) Prototype `point` is an explicit array `[slot, eb_hash]`; `main` writes it as a
CDDL group `(slot, eb_hash)`.

| Tag | Prototype message | Current message | From → To | Agency | Yaci handling |
| :-- | :---------------- | :-------------- | :-------- | :----- | :------------ |
| 0 | `MsgLeiosNotificationRequestNext` `[0]` | same | StIdle→StBusy | client | send |
| 1 | `MsgLeiosBlockAnnouncement` `[1, announcement]` | `[1, announcement_header]` | StBusy→StIdle | server | **opaque** announcement |
| 2 | `MsgLeiosBlockOffer` `[2, point, eb_size]` | `[2, point]` | StBusy→StIdle | server | parse `point` (+`eb_size` in prototype) |
| 3 | `MsgLeiosBlockTxsOffer` `[3, point]` | same | StBusy→StIdle | server | parse `point` |
| 4 | `MsgLeiosVotes` `[4, [1* vote]]` (bodies) | `MsgLeiosVotesOffer` `[4, [1*(slot,voter_id)]]` (ids) | StBusy→StIdle | server | prototype: **opaque** vote list; current: parse `(slot,voter_id)` |
| 5 | `MsgClientDone` `[5]` | same | StIdle→StDone | client | send |

### LeiosFetch (protocol 19)

**`MUSASHI_PROTOTYPE` (188183b)** — smaller; range/votes not yet implemented:

```cddl
msgLeiosBlockRequest    = [0, point]
msgLeiosBlock           = [1, endorser_block]              ; endorser_block = { * hash => word32 } (opaque here)
msgLeiosBlockTxsRequest = [2, point, bitmaps]
msgLeiosBlockTxs        = [3, point, bitmaps, tx_list]     ; server echoes point + bitmaps
msgClientDone           = [9]
; msgLeiosBlockRangeRequest / Next / Last  — commented out: "not implemented yet in leios-prototype"
point   = [slot, eb_hash]
bitmaps = { * word16 => word64 }                           ; indefinite map; 64-tx window => 64-bit presence
tx_list = [ *tx.tx ]                                       ; each tx opaque
```

Prototype states: `StIdle` (client), `StBlock`, `StBlockTxs` (server), `StDone` —
the prototype subset of the full state machine (no `StVotes` / `StBlockRange`).

**`BLUEPRINT_CURRENT` (e773b95)** — the fuller intended protocol:

```cddl
msgLeiosBlockRequest           = [0, point]
msgLeiosBlock                  = [1, endorser_block]
msgLeiosBlockTxsRequest        = [2, point, bitmaps]
msgLeiosBlockTxs               = [3, tx_list]              ; (README table shows "point, bitmaps, tx_list")
msgLeiosVotesRequest           = [4, [1* (slot, voter_id)]]
msgLeiosVoteDelivery           = [5, [1* vote]]
msgLeiosBlockRangeRequest      = [6, start_slot, end_slot, start_hash, end_hash]
msgLeiosNextBlockAndTxsInRange = [7, endorser_block, tx_list]
msgLeiosLastBlockAndTxsInRange = [8, endorser_block, tx_list]
msgClientDone                  = [9]
vote = [ election_id: slotNo (word64), persistent_voter_id: word16,
         eligibility_signature: bytes .size 48, endorser_block_hash: hash,
         vote_signature: bytes .size 48 ]
```

Current states: `StIdle`, `StBlock`, `StBlockTxs`, `StVotes`, `StBlockRange`
(loops to itself on tag 7, returns to `StIdle` on tag 8), `StDone`.

| Tag | Prototype | Current | From → To | Agency |
| :-- | :-------- | :------ | :-------- | :----- |
| 0 | `MsgLeiosBlockRequest` `[0, point]` | same | StIdle→StBlock | client |
| 1 | `MsgLeiosBlock` `[1, endorser_block]` (opaque) | same | StBlock→StIdle | server |
| 2 | `MsgLeiosBlockTxsRequest` `[2, point, bitmaps]` | same | StIdle→StBlockTxs | client |
| 3 | `MsgLeiosBlockTxs` `[3, point, bitmaps, tx_list]` | `[3, tx_list]` | StBlockTxs→StIdle | server |
| 4 | — | `MsgLeiosVotesRequest` `[4, [1*(slot,voter_id)]]` | StIdle→StVotes | client |
| 5 | — | `MsgLeiosVoteDelivery` `[5, [1* vote]]` | StVotes→StIdle | server |
| 6 | — | `MsgLeiosBlockRangeRequest` `[6, start_slot, end_slot, start_hash, end_hash]` | StIdle→StBlockRange | client |
| 7 | — | `MsgLeiosNextBlockAndTxsInRange` `[7, endorser_block, tx_list]` | StBlockRange→StBlockRange | server |
| 8 | — | `MsgLeiosLastBlockAndTxsInRange` `[8, endorser_block, tx_list]` | StBlockRange→StIdle | server |
| 9 | `MsgClientDone` `[9]` | same | StIdle→StDone | client |

Prototype → current differences: prototype `BlockTxs` **echoes `point, bitmaps`**
before `tx_list` (`main`'s CDDL body says `[3, tx_list]` but its README table
lists `point, bitmaps, tx_list` — so the echoed form is what's live); votes (4/5)
and range (6/7/8) exist only in `main`. The vote shape also differs (4-field
prototype vs 5-field current) — irrelevant at this layer since votes stay opaque.

The **first Yaci PR implements only the prototype `LeiosFetch` set**
(`BlockRequest`, `Block`, `BlockTxsRequest`, `BlockTxs`, `Done`); votes/range
message classes may be reserved as TODOs but must not be exposed as working API
until a target peer/profile supports them.

## Yaci Design

### Package layout

Add under `core/src/main/java/com/bloxbean/cardano/yaci/core/`:

```
protocol/leios/                 (shared)
  LeiosProtocolProfile.java     (enum: MUSASHI_PROTOTYPE, BLUEPRINT_CURRENT[inactive])
  LeiosPoint.java               (long slot, byte[] ebHash)
  LeiosRawCbor.java             (byte[] cbor; opaque wrapper, hex only as a derived helper)
  LeiosTxBitmap.java            (ordered map: int window -> long mask; fromIndices(...), firstN(int))
  messages/ , serializers/      (shared message bases if useful)
protocol/leiosnotify/           (LeiosNotifyState, LeiosNotifyStateBase, LeiosNotifyAgent,
                                 [LeiosNotifyServerAgent], LeiosNotifyListener, messages/, serializers/)
protocol/leiosfetch/            (LeiosFetchState, LeiosFetchStateBase, LeiosFetchAgent,
                                 [LeiosFetchServerAgent], LeiosFetchListener, messages/, serializers/)
```

Reference patterns: **`protocol/peersharing/**`** (closest N2N template: enum
`State` + `StateBase.handleInbound` tag-dispatch + `Agent` + `Listener` +
`messages/` + `serializers/`) and **`protocol/appmsg/n2n/**`** (adds a
`*ServerAgent`). **No new models for EB/tx/vote/cert** — they are opaque CBOR
values on the message objects; the follow-up PR adds `model/leios/*` and
structured decoders.

### Mapping onto Yaci's mini-protocol contract

- **`Agent<T>`** (`getProtocolId`, `buildNextMessage`, `processResponse`,
  `isDone`): `LeiosNotifyAgent` (id 18) mirrors `PeerSharingAgent` — a queue of
  outstanding `RequestNext`s, `MsgClientDone` on shutdown, `reset()` → `StIdle`.
  `LeiosFetchAgent` (id 19) tracks the outstanding request so `processResponse`
  can pair a response with its `LeiosPoint` (chain-sync "pending data" idiom).
  The `0x8000` responder flag and >64 KiB segmentation are handled by the base
  `Agent`.
- **`State`** (`nextState`, `hasAgency`, `handleInbound`): enums per the state
  machines above; `hasAgency(isClient)` = `isClient` in `StIdle`, `!isClient` in
  every server-agency state (`StBusy` for Notify; `StBlock` / `StBlockTxs` /
  `StVotes` / `StBlockRange` for Fetch), `false` in `StDone`.
- **`StateBase.handleInbound(byte[])`**: read `array[0]` as the tag and dispatch to
  the matching serializer (exactly like `PeerSharingStateBase.handleInbound`).
- **`Serializer<T>`** (`co.nstant.in.cbor`): one enum-singleton per message.
  `LeiosPoint` en/decodes `[slot (uint), eb_hash (bstr32)]`; opaque payload fields
  are decoded only to the CBOR value boundary and stored as `LeiosRawCbor` bytes
  using Yaci's normal CBOR serialization unless a dedicated byte-preserving
  extractor is added. This keeps unknown/`any` structures opaque and avoids domain
  decoding, but it is not a guarantee that inbound indefinite/canonical encoding
  details remain byte-for-byte identical.
- **Profile awareness lives in the serializers/agent, not the payloads.** Because
  payloads are opaque, the only profile-divergent code is envelope arity/semantics:
  `MsgLeiosBlockOffer` (`[2, point]` vs `[2, point, eb_size]`), notify tag 4
  (opaque bodies vs `(slot, voter_id)` ids), and `MsgLeiosBlockTxs`
  (`[3, point, bitmaps, tx_list]` vs `[3, tx_list]`). Serializers switch on
  `LeiosProtocolProfile`; decoders SHOULD accept the extra fields defensively.

### Shared models & bitmap handling

`LeiosTxBitmap` is a CBOR map `word16 window → word64 mask`. Implementation:
encode as an **indefinite-length** map for prototype compatibility; decode both
indefinite and definite maps; preserve key order deterministically for tests;
document the bit convention as **64 transactions per window, tx index 0 = MSB of
window 0** *(bit convention is an implementation detail not pinned by the CDDL
comment — confirm against a live peer)*; provide `firstN(int count)` and
`fromIndices(...)` helpers **without decoding transactions**.

### Agents

**`LeiosNotifyAgent`** (id 18) — states `StIdle`/`StBusy`/`StDone`. Outbound from
`StIdle`: `MsgLeiosNotificationRequestNext`, `MsgClientDone`. Inbound in `StBusy`
(prototype): `MsgLeiosBlockAnnouncement` (opaque), `MsgLeiosBlockOffer`
(`LeiosPoint` + `ebSize`), `MsgLeiosBlockTxsOffer` (`LeiosPoint`), `MsgLeiosVotes`
(opaque list). Start with **one in-flight `RequestNext`**; pipelining deferred
until admitted depth is documented. Listener: `onBlockAnnouncement(LeiosRawCbor)`,
`onBlockOffer(LeiosPoint, long ebSize)`, `onBlockTxsOffer(LeiosPoint)`,
`onVotes(List<LeiosRawCbor>)`, `onNotifyError(Throwable)`.

**`LeiosFetchAgent`** (id 19) — prototype states `StIdle`/`StBlock`/
`StBlockTxs`/`StDone`. Outbound from `StIdle`: `MsgLeiosBlockRequest`,
`MsgLeiosBlockTxsRequest`, `MsgClientDone`. Inbound: `MsgLeiosBlock` (opaque EB),
`MsgLeiosBlockTxs` (echoed `LeiosPoint` + `LeiosTxBitmap` + opaque `tx_list`).
**One outstanding request per peer** (small FIFO ok; concurrency deferred). Retain
echoed point/bitmap even when the request context already knows them. Public:
`requestBlock(LeiosPoint)`, `requestBlockTxs(LeiosPoint, LeiosTxBitmap)`,
`done()`. Listener: `onBlock(LeiosPoint requested, LeiosRawCbor eb)`,
`onBlockTxs(LeiosPoint requested, LeiosPoint response, LeiosTxBitmap bitmap,
LeiosRawCbor txList)`, `onFetchError(Throwable)`.

### Handshake & activation

Leios agents are **inactive unless explicitly configured**. Add a Musashi helper
(in `N2NVersionTableConstant` or an equivalent builder): network magic **164**,
negotiated N2N version **≥ `PROTOCOL_V15`** *(assumed Leios-enabling version;
Yaci already advertises up to V15 — confirm the exact version and any version-data
capability bit from a live handshake)*. Attach the two Leios agents **only** when
the accepted version is compatible; otherwise the client behaves exactly as today.
The helper must **not** replace the existing app-layer version-100 path; ids 18/19
route through the existing mux dispatch by protocol id.

### Helper-level integration

Keep it small in the first PR: a focused `LeiosNetworkClient` (or a builder method
on an existing peer client) that wires the handshake + notify + fetch agents to
`TCPNodeClient(host, port, handshakeAgent, agents...)`. First usable workflow:
(1) connect to a Musashi peer, magic 164; (2) negotiate a Leios-capable version;
(3) start `LeiosNotify`; (4) on `BlockOffer`, optionally `requestBlock(point)`;
(5) on `BlockTxsOffer`, optionally `requestBlockTxs(point, bitmap)`; (6) emit raw
CBOR events, no Store persistence.

## Current testnet posture (conservative)

Treat `BlockAnnouncement`/`Block`/`Votes`/`tx_list` as opaque CBOR values.
Request EB txs only after a `BlockTxsOffer` for the same `LeiosPoint`. Don't
request all txs of a large EB by default — start with `LeiosTxBitmap.firstN(64)` and make callers opt
into larger windows. Keep request timeouts and disconnect handling explicit
(prototype relays may reset on unsupported/poorly-timed requests). Log protocol
id, state, tag, EB point, EB size, bitmap windows — **not** raw tx payloads. This
layer is **not** a stable API promise; it validates Yaci's mux/state-machine/
request-response before block-serialization work starts.

## Phased plan

1. **Network skeleton & fixtures** — shared models, `LeiosProtocolProfile`, id
   constants 18/19, fixture tests for prototype tags/shapes, CDDL source comments
   with the two commit hashes. *Exit:* all prototype envelopes encode/decode by
   profile; no
   block/tx/vote/cert deserializer exists.
2. **LeiosNotify** — state, messages, serializers, agent, listener; transition
   tests for request-next, each notification variant, done. *Exit:* agent emits
   listener events with raw payloads; does not self-fetch.
3. **LeiosFetch** — state, messages, serializers, agent, listener; bitmap helper +
   golden tests; one-request-at-a-time queue. *Exit:* requests EB bodies and tx
   payloads; responses preserve opaque CBOR payload values; bitmap encoding
   covered by golden tests.
4. **Handshake & smoke client** — Musashi version-table helper, optional agent
   wiring, a default-disabled smoke/integration test. *Exit:* a developer can
   connect to a Musashi relay, observe notifications, optionally fetch opaque EB/
   tx-list CBOR values; existing ChainSync/BlockFetch/app-message tests unchanged.
5. **Blueprint-drift follow-up** — add the `BLUEPRINT_CURRENT` profile (`main`
   shape) when needed, incl. `VotesOffer`/`VotesRequest`/`VoteDelivery` and the
   range messages, only after a peer supports them. *Exit:* behavior selectable by
   profile; prototype-only shapes don't leak into stable public APIs.

## Verification

- **Unit (serializers):** for Yaci-constructed messages, assert encode → decode →
  re-encode matches the expected profile encoding; for inbound opaque payloads,
  assert tag/arity/profile handling and payload value preservation without domain
  decoding. Require byte-for-byte inbound payload preservation only if a dedicated
  byte-preserving extractor is implemented. Validate sample encodings against the
  **vendored `messages.cddl` at both commits** (prototype required; current as the
  follow-up profile lands).
- **Unit (state machines):** table-driven legal-transition + agency tests per
  state; illegal messages rejected (mirror `peersharing` tests).
- **Bitmap golden tests:** indefinite-map encoding; `firstN`/`fromIndices` MSB-
  first convention; definite-map decode tolerance.
- **Integration (default-disabled):** drive `LeiosNetworkClient` against a Musashi
  relay (magic 164); assert the notify→fetch loop yields ≥1 EB body and its tx
  closure as opaque CBOR bytes, and that a non-Leios peer still connects with the
  Leios agents simply inactive.

## Consequences

**Positive:** small, reviewable, self-contained surface that lands Leios transport
independently of still-moving payload formats; the opaque-CBOR boundary means
later EB/tx/vote/cert churn touches only the follow-up PR; two pinned profiles let
Yaci experiment on the live testnet today while keeping a clean path to the final
shape; zero risk to existing ChainSync/BlockFetch/PeerSharing (agents attach only
on a Leios-capable handshake). Resolves the "read the ids from the blueprint, don't
guess" item: **18 = LeiosNotify, 19 = LeiosFetch**.

**Negative / costs:** two state machines + ~a dozen prototype message classes +
serializers to build against a **proposed** spec (mitigated by opaque payloads,
pinned commits, and CDDL fixture tests); without payload decoding the PR yields
**opaque CBOR bytes** — a transport foundation whose end-to-end value needs the
follow-up PR; profile divergence adds a little serializer branching.

## Risks & unknowns

| Risk | Mitigation |
| :--- | :--------- |
| **Provisional spec / tags** (`any`/`REVIEW`, may change to final CIP-0164) | Opaque payloads; pin both profiles to exact commits; CDDL fixture tests; isolate in new packages. |
| **Prototype ≠ current Blueprint** (`BlockOffer` `eb_size`, tag-4 votes vs offer, `BlockTxs` echo) | Explicit `LeiosProtocolProfile`; implement prototype first; decode extra fields defensively. |
| **Bitmap encoding** (definite-map or reversed bit order may be rejected by prototype peers) | Encode outbound bitmaps as indefinite-length maps; document MSB-first 64-tx windows; golden tests; decode inbound maps without changing their transaction-index semantics. |
| **Premature / oversized requests cause disconnects** | Request txs only after an offer; default `firstN(64)`; explicit timeouts + disconnect handling. |
| **Large payloads exceed one mux segment** | Reuse `Agent`'s existing >64 KiB segmentation. |
| **Handshake version / magic** (v15 assumed; capability bit unknown) | Yaci already advertises V15; confirm negotiated version + version-data from a live handshake; keep Musashi helper isolated from mainnet/preprod. |
| **Pipeline depth** (admitted depth is `TODO` in the spec) | Start with one in-flight request; make it configurable once documented. |
| **Ids 18/19 vs code assuming the old mini-protocol set** | Route via existing mux dispatch by id; add tests asserting 18/19 don't collide. |
| **Scope creep into payload decoding** | Hard non-goals list; opaque `LeiosRawCbor`; block-serialization is a separate PR. |

## Open questions

- Exact Leios-enabling N2N handshake version and any version-data capability flag
  (confirm from a live Musashi handshake).
- Admitted pipeline depth for LeiosNotify / LeiosFetch without peer penalties.
- Final bitmap structure (map vs roaring bitmap vs other) and the definitive
  bit-order convention.
- Whether final `LeiosFetch` keeps echoed `point,bitmaps` on `BlockTxs` or adopts
  the `main`-branch `[3, tx_list]` body.
- Whether `tx_list` should later surface as one CBOR blob or a list of per-tx CBOR
  blobs (a block-serialization-PR decision).

## Acceptance criteria

- This ADR exists and supersedes 0005/0006.
- No Store dependency; no block-level serializers/deserializers added.
- `LeiosNotify` (18) and `LeiosFetch` (19) are feature-gated and profile-aware.
- Musashi prototype behavior is testable with raw-CBOR payloads.
- Existing Yaci protocols continue to pass their current tests.

## Key files / new packages

**New:** `core/.../protocol/leios/{LeiosProtocolProfile,LeiosPoint,LeiosRawCbor,LeiosTxBitmap}.java`;
`core/.../protocol/leiosnotify/**`; `core/.../protocol/leiosfetch/**`;
`helper/.../LeiosNetworkClient.java` (+ `LeiosDataListener`).
**Touched (only if a live handshake requires it):**
`core/.../protocol/handshake/util/N2NVersionTableConstant.java` (Musashi helper);
helper client wiring to attach the Leios agents when a Leios-capable version is
negotiated.
**Reference patterns:** `core/.../protocol/peersharing/**`, `core/.../protocol/appmsg/n2n/**`.
**Deferred to the block-serialization PR:** `model/leios/*` (EndorserBlock, txs,
votes, certificate), structured decoders for the opaque payloads, BLS
verification, Dijkstra `Era`/`Block` changes, Yaci-Store integration.
