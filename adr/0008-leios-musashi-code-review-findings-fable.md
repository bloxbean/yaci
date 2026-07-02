# ADR 0008: Leios (Musashi) Mini-Protocol Implementation — Code Review Findings

Date: 2026-07-02

Status: Proposed

> Authored by Claude (Fable 5). This ADR records the verified findings of a
> multi-agent code review (xhigh effort: 6 finder agents across independent
> correctness/cleanup angles, 44 candidate findings, each adversarially
> verified by an independent agent; 4 refuted, survivors deduplicated to 15)
> over the Leios mini-protocol implementation on branch
> `feat/leios_protocol_impl`. Scope: `core/.../protocol/leios/`,
> `core/.../protocol/leiosfetch/`, `core/.../protocol/leiosnotify/`, the
> `N2NVersionTableConstant` handshake change, `helper/LeiosNetworkClient.java`,
> `helper/listener/LeiosDataListener.java`, and related tests.
>
> Companions: ADR 0005 / 0006 / 0007 (design and plan records for the same
> implementation). This document is the review record against that design and
> the input for the remediation PR(s).

## Context

The Leios implementation (per ADR 0007) adds two client-side N2N mini-protocol
agents — `LeiosNotifyAgent` (mux protocol 18) and `LeiosFetchAgent` (mux
protocol 19) — plus CBOR serializers that treat Leios payloads as opaque raw
CBOR, and a `LeiosNetworkClient` helper facade targeting the Musashi prototype
testnet (magic 164, N2N version ≥ 15).

The review confirmed the overall state-machine and serializer structure is
sound, but found a recurring failure mode across the highest-severity items:
**error paths are one-way streets**. Any parse error, listener exception, or
failed Netty write leaves an agent in a state that nothing can drive out of —
the connection stays up, `isLeiosActive()` stays true, and the stream is
silently dead. A second theme is that the inbound mux pipeline's
decode→re-encode step is not byte-faithful for certain wire-legal CBOR
encodings, which can desync the stream entirely.

## Findings

Ranked most-severe first. Verdicts are from the independent verification pass
(`CONFIRMED` = mechanism proven against the code and/or by execution;
`PLAUSIBLE` = mechanism real but trigger not proven wire-legal).

### Critical — protocol/stream-breaking

#### 1. Empty indefinite-length CBOR maps corrupt the leiosfetch stream — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leios/serializers/LeiosCborUtil.java:56`

The known cbor-java 0.9 bug — an empty chunked map `BF FF` re-encodes to a
lone `BF`, dropping the break byte — is worked around only on the **outbound**
path (`serializeTxBitmapBytes`). The inbound mux path
(`MiniProtoStreamingByteToMessageDecoder`) decodes and **re-encodes** every
frame with the same buggy encoder, and has no counterpart workaround.

Failure scenario (mechanism proven by executing cbor-java 0.9): client calls
`requestFirstBlockTxs(point, 0)` or `requestBlockTxs(point, LeiosTxBitmap.empty())`;
the node echoes `msgLeiosBlockTxs [3, point, {_}, txList]` with the empty
indefinite bitmap encoded as `BF FF`. The re-encoded payload is one byte
shorter (verified: 41-byte frame → 40 bytes), fails CBOR parsing
("Unexpected end of stream"), surfaces as `MsgLeiosFetchError` (request queue
cleared, no txs delivered) — and the 1-byte length mismatch leaves a stray
break byte in the per-protocol buffer, **desyncing every subsequent leiosfetch
message on the connection**. The same corruption fires for any fetched empty
endorser block whose map is encoded as `BF FF`.

#### 2. Notify stream stalls permanently after any parse error — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leiosnotify/LeiosNotifyAgent.java:69`

On `MsgLeiosNotifyError` or an unexpected inbound message, `receiveResponse`
resets to `StIdle` and returns **before** `requestNextIfStreaming()`. Nothing
else drives the agent.

Failure scenario: the node sends one message the client cannot parse (a
new/unknown notify tag after a node upgrade, or any malformed frame) —
`LeiosNotifyStateBase.deserialize` converts it to `MsgLeiosNotifyError`,
`onNotifyError` fires, and no further `MsgLeiosNotificationRequestNext` is
ever sent. The client silently stops receiving all Leios block announcements,
offers, and votes while the connection stays up and `isLeiosActive()` reports
true. No disconnect/reconnect recovery occurs.

#### 3. Fetch error recovery can pair a response with the wrong request — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leiosfetch/LeiosFetchAgent.java:204`
(also `:208`, `:89`)

`clearOutstandingAndReturnIdle` forces the state back to `StIdle` and bumps
`writeGeneration` while a request write may already be on the wire, so the
server's response to that in-flight request gets paired with the **next**
outstanding request. It also silently discards all queued requests with no
per-request error.

Failure scenario: client sends `MsgLeiosBlockRequest(A)` (write in flight or
flushed), then a garbage segment on protocol 19 (e.g. finding 1) produces
`MsgLeiosFetchError`: state resets, A's write callback is treated as stale
even though A reached the server. The application calls `requestBlock(B)`;
the server replies with block(A) first, which arrives in state `StBlock` with
`outstandingRequest = B`, so `handleBlock` invokes
`listener.onBlock(requestedPoint=B, endorserBlock=blockOfA)` — **block A is
delivered under point B**. The subsequent block(B) response is rejected as
invalid. Any other queued requests vanish (`requestQueue.clear()`), so their
callers wait forever with neither a response nor an error callback.

#### 4. Failed Netty write permanently wedges the fetch agent — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leiosfetch/LeiosFetchAgent.java:227`
(also `:229`)

`writeNextMessageIfReady` sets `writeInFlight = true`, but `Agent.writeMessage`
only invokes the completion callback on write **success** — a failed or skipped
write (channel goes inactive between the `isChannelActive()` check and the
write, or `writeAndFlush` fails) never runs `completeWrite`, so `writeInFlight`
is stuck true with no generation bump.

Failure scenario: TCP drops in that window; every later
`requestBlock()`/`requestBlockTxs()` is accepted and enqueued but
`buildNextMessage`/`writeNextMessageIfReady` permanently return early —
requests are never sent, no error is surfaced. Recovery only happens via a
disconnect-triggered `reset()`; with `autoReconnect(false)` or retries
exhausted, the fetch agent silently stalls forever.

#### 5. A throwing user listener stalls both agents — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leiosnotify/LeiosNotifyAgent.java:83`

An exception thrown by any user listener during notification dispatch
propagates out of `super.receiveResponse` **before** `requestNextIfStreaming()`
runs — a distinct trigger from finding 2, with no `onNotifyError` callback at
all.

Failure scenario: a `LeiosDataListener.onBlockAnnouncement` (or
`onBlockOffer`/`onVotes`) implementation throws once (e.g. a transient NPE in
application code). The exception skips `requestNextIfStreaming()`, unwinds
through `MiniProtoClientInboundHandler.channelRead` into Netty's tail handler
(logged, channel stays open), and no `RequestNext` is ever sent again. The
same pattern in `LeiosFetchAgent.receiveResponse` (listener throwing in
`onBlock`/`onBlockTxs` skips `writeNextMessageIfReady`) strands queued fetch
requests until the next `requestBlock` call. Listener dispatch should be
wrapped (as elsewhere in yaci's helper listeners).

### High — lifecycle and handshake

#### 6. Notify agent lacks an in-flight write guard — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leiosnotify/LeiosNotifyAgent.java:62`
(also `:142`)

Unlike `LeiosFetchAgent`, `LeiosNotifyAgent` has no `writeInFlight` guard:
`currentState` remains `StIdle` until the asynchronous Netty write-future
callback runs `sendRequest`, so a check-then-act race lets a second client
message be written while a `MsgLeiosNotificationRequestNext` is still in
flight.

Failure scenario: `LeiosNetworkClient.shutdown()` runs while a `RequestNext`
write has not yet completed: `buildNextMessage` sees `StIdle` + `shutDown` and
writes `MsgClientDone` as well; the peer receives ClientDone while it has
agency in `StBusy` and aborts the connection as a protocol violation. Locally
the ClientDone callback's `sendRequest` no-ops, so the agent never reaches
`StDone` and `isDone()` stays false.

#### 7. `shutdown()` never sends ClientDone in the normal streaming state — CONFIRMED

`helper/src/main/java/com/bloxbean/cardano/yaci/helper/LeiosNetworkClient.java:84`
(also `:82`)

`shutdown()` tries to send the notify `MsgClientDone` via
`leiosNotifyAgent.sendNextMessage()`, but in the steady streaming state the
notify agent sits in `StBusy` (client has no agency, `buildNextMessage`
returns null), so nothing is written. The same applies to
`leiosFetchAgent.done()` when a fetch response is outstanding. The socket is
then closed abruptly: the Musashi peer observes a mini-protocol violation /
unclean disconnect instead of graceful termination.

#### 8. Handshake refusal passes null `AcceptVersion` to `onLeiosNotActivated` — CONFIRMED

`helper/src/main/java/com/bloxbean/cardano/yaci/helper/LeiosNetworkClient.java:188`
(also `:186`)

The `handshakeError` adapter forwards `handshakeAgent.getProtocolVersion()`,
but `HandshakeAgent` explicitly calls `setProtocolVersion(null)` before firing
`handshakeError` — so the callback receives null, unlike every other
invocation site. A listener that dereferences it (e.g.
`acceptVersion.getVersionNumber()`) throws NPE inside the handshake listener
`forEach`, aborting notification of remaining listeners.

#### 9. Client-initiated shutdown fires `onDisconnect` — CONFIRMED

`helper/src/main/java/com/bloxbean/cardano/yaci/helper/LeiosNetworkClient.java:253`

`handleDisconnect` fires `LeiosDataListener.onDisconnect` even for a
client-initiated `shutdown()` (Session dispose → closeFuture →
`agent.disconnected()`), so applications cannot distinguish an intentional
close from a lost connection. The bundled `LeiosMusashiSmokeTest` has to
hand-roll a `stopping` AtomicBoolean to suppress the spurious callback — the
tell that the API is missing this state.

#### 10. `isLeiosCompatible` treats any v15+ node as Leios-capable — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/handshake/util/N2NVersionTableConstant.java:120`
(also `:121`)

Any negotiated N2N version in 15..99 with a matching network magic is treated
as Leios-capable, but v15 is a regular cardano-node version ("srv support" per
the constant's own comment) that implies nothing about Leios mini-protocols
being served.

Failure scenario: a user builds `LeiosNetworkClient(host, port, PREPROD_MAGIC)`
against a future cardano-node release that negotiates v15:
`handleHandshakeComplete` activates Leios, `LeiosNotifyAgent` sends on mux
protocol 18, and the node terminates the connection as a mux protocol
violation — the client disconnects instead of reporting "Leios not
supported". Gating on the Musashi-specific magic (or a dedicated capability
flag) is the deeper fix; version ≥ 15 is a prototype-only special case baked
into shared handshake constants.

### Medium / Low — robustness and cleanups

#### 11. Arity guards reject wire-legal indefinite-length arrays — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leios/serializers/LeiosCborUtil.java:37`

`deserializePointArray`'s `items.size() != 2` check and the
`messageItems`/`requireArity` helpers count the trailing `SimpleValue.BREAK`
item that cbor-java appends when decoding chunked arrays, while the sibling
decoders (`deserializeTxBitmap`, `deserializeRawCborArrayItems`) deliberately
skip BREAK. A peer encoding a message frame or point as an indefinite-length
array (e.g. `MsgLeiosBlockOffer` as `9F 02 <point> <eb_size> FF`) trips the
guard → `MsgLeiosNotifyError`/`MsgLeiosFetchError` → the finding-2/3 error
paths, even though the same message's chunked bitmap map field is explicitly
supported.

#### 12. `MsgLeiosVotes` rejects an empty votes list — PLAUSIBLE (likely spec-conformant as-is)

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leiosnotify/messages/MsgLeiosVotes.java:16`

The constructor throws on an empty list, so a zero-vote `MsgLeiosVotes` would
become a fatal `MsgLeiosNotifyError` (and, via finding 2, a stalled stream).
However, two duplicate candidates were **refuted** during verification: the
blueprint CDDL at the Musashi prototype commit defines
`msgLeiosVotes = [4, [1* vote]]` — one or more votes — so the guard arguably
matches the spec. Recommendation: keep the guard, but note it becomes a stream
killer only in combination with finding 2; fixing finding 2 defuses this.

#### 13. `toRawCbor` re-encodes instead of slicing wire bytes — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leios/serializers/LeiosCborUtil.java:84`

Opaque payloads (endorser block, tx list, votes, announcement) are extracted
by decoding the wire CBOR into a DataItem tree and re-encoding it, instead of
slicing the original payload bytes. The "raw" bytes are therefore not
guaranteed byte-identical to the wire (cbor-java always re-encodes in minimal
form): a consumer that hashes `endorserBlock.getCbor()` to verify it against
the announced `LeiosPoint` `ebHash` gets a mismatch on any non-minimally
encoded field and rejects a valid block. Even when bytes match, every received
multi-hundred-KB EB pays a full decode + re-encode + two array copies
(`LeiosRawCbor` ctor and `getCbor`) on the hot receive path. Related
(confirmed, lower severity): `validatedRawCborBytes` re-parses
already-validated bytes and makes two copies on every `MsgLeiosBlock`/
`MsgLeiosBlockTxs` serialization.

#### 14. Keep-alive scheduling is left to every caller — CONFIRMED

`helper/src/main/java/com/bloxbean/cardano/yaci/helper/LeiosNetworkClient.java:164`

The client sends exactly one keep-alive at handshake and leaves periodic
keep-alive to the application (the smoke test hand-rolls a 10s virtual-thread
loop). A production user who doesn't copy that pattern gets dropped by the
peer's inactivity timeout and sees periodic `onDisconnect` with no obvious
cause. The client owns the connection; it should own the timer.

#### 15. `BLUEPRINT_CURRENT` is dead plumbing — CONFIRMED

`core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/leios/LeiosProtocolProfile.java:5`

Both agent constructors throw for `BLUEPRINT_CURRENT`, yet the profile
parameter is threaded through every serializer `deserialize(di, profile)`,
`requireMusashi` guard, and both `StateBase.deserialize` dispatchers — ~15
branches no runtime path can reach, plus tests asserting the unreachable
throws. Drop the enum value and profile parameters until a second profile
actually exists; adding it back later is mechanical.

### Refuted during verification (recorded to prevent re-litigating)

- **`PROTOCOL_V15` in shared version tables breaks existing clients
  (peer sharing / SRV addresses)** — REFUTED. Upstream ouroboros-network's
  `NodeToNodeV_15` "SRV support" is a CIP-155 local DNS-SRV resolution feature
  for ledger/root peers, not a peer-sharing wire change; the peer-sharing CDDL
  (v14+, unchanged for v15/16) has no SRV/domain address type, and v15
  handshake version data is wire-identical to v14 for everything yaci
  implements.
- **Notify error path resurrects a completed `StDone` state machine** —
  REFUTED. The scenario is guarded elsewhere and not constructible.
- **Empty `[4, []]` votes message is wire-legal** — REFUTED (×2). CDDL says
  `1* vote`; see finding 12.

## Decision

Yaci is a dependency of existing critical products (yaci-store, DevKit,
downstream chain-sync/block-fetch consumers), so remediation is **split by
blast radius**: changes that touch code executed by every existing yaci client
go into a separate, heavily-tested PR; everything that lives purely in the new
Leios files ships with the Leios feature PR at no risk to existing users.

### Part A — Global / shared-code changes (regression risk; heavy testing, separate PR)

These modify pre-existing files on the hot path of **every** N2N/N2C
mini-protocol (chain-sync, block-fetch, tx-submission, handshake, keep-alive,
peer sharing). Each must be covered by a full regression pass: existing
protocol unit/integration tests, multi-epoch sync against a real Haskell node
(the `test-haskell-sync` regression), sync against preprod/mainnet relays, and
a yaci-store smoke run.

- **A1. Inbound mux decode→re-encode fidelity** —
  `MiniProtoStreamingByteToMessageDecoder` / `CborSerializationUtil`
  (root cause of findings **1**, **13** wire-fidelity, and the environment for
  **11**).
  - *Full fix:* stop re-encoding inbound frames; hand agents slices of the
    original wire bytes. Fixes the empty-chunked-map corruption, makes raw
    CBOR byte-identical to the wire (hash-verifiable EBs), and removes a
    decode+re-encode from the receive path of every protocol.
  - *Contained alternative:* patch only the empty-chunked-map break-byte case
    in the re-encode step. Much smaller diff, but still global — the encoder
    is shared by all protocols.
  - Regression exposure: **highest** — every inbound message of every
    protocol flows through this code.
- **A2. `Agent.writeMessage` write-failure signal** (root cause of finding
  **4**). Additive change: a new overload/callback invoked on write failure;
  the existing success-only path stays byte-for-byte identical so existing
  agents are unaffected unless they opt in. Regression exposure: low
  (additive), but the base class is shared by all agents, so it belongs in
  the shared PR.
- **A3. `PROTOCOL_V15` in the shared `v4AndAbove`/`v11AndAbove` tables**
  (already in this branch's diff; context for finding **10**). The review
  verified v15 is wire-identical to v14 for everything yaci implements (see
  Refuted section), but it still changes the version every existing client
  negotiates against every peer — it must ride the global PR's regression
  run, not the Leios PR.
  - *De-risking alternative (recommended):* revert the shared tables to v14
    and give `LeiosNetworkClient` its own Leios-specific version table. This
    moves the entire item into Part B and leaves existing clients'
    negotiation behavior untouched.
- **A4. (Optional) `HandshakeAgent` nulling `protocolVersion` before
  `handshakeError`** (root cause of finding **8**). Not required — the Leios
  PR fixes it locally (B7). Touch `HandshakeAgent` only as deliberate shared
  cleanup, if ever.

### Part B — Leios-isolated changes (no regression risk; ship with the Leios PR)

Everything below lives in files new on this branch (`protocol/leios/`,
`protocol/leiosfetch/`, `protocol/leiosnotify/`, `LeiosNetworkClient`,
`LeiosDataListener`, their tests). No existing consumer loads these classes,
so they cannot regress existing products. If the A3 alternative is taken, the
Leios feature PR has **zero shared-code diff**.

- **B1.** Re-arm the notify loop on error paths (finding **2**): after
  `MsgLeiosNotifyError`/invalid inbound, either re-issue `RequestNext` or
  fail loudly — never return to silent idle.
- **B2.** Fetch error semantics (finding **3**): on a fetch error, fail every
  outstanding and queued request via an explicit per-request error callback
  (or disconnect) instead of clearing the queue; never re-pair a late
  response with the next request.
- **B3.** Wrap user-listener dispatch in both agents (finding **5**) so an
  application exception cannot kill the protocol loop.
- **B4.** Local `writeInFlight` recovery (finding **4** mitigation): re-check
  channel state / generation timeout inside `LeiosFetchAgent` so a lost write
  cannot wedge the agent; adopt the A2 callback once the shared PR lands.
- **B5.** In-flight write guard in `LeiosNotifyAgent` (finding **6**),
  mirroring the fetch agent, so shutdown cannot race a `RequestNext`.
- **B6.** Graceful shutdown (finding **7**): defer `MsgClientDone` until the
  agent regains agency (StIdle) instead of attempting it from StBusy — or
  document abrupt close as intentional for the prototype.
- **B7.** Null-safe `onLeiosNotActivated` in the `LeiosNetworkClient`
  handshake adapter (finding **8**, local fix).
- **B8.** Track client-initiated close so `onDisconnect` only fires for real
  connection loss (finding **9**); removes the smoke test's hand-rolled
  `stopping` flag.
- **B9.** Gate Leios activation on the Musashi network magic / explicit
  config flag instead of `version >= 15` (finding **10**). `isLeiosCompatible`
  is only called from the Leios path, so this is isolated even though it
  lives in `N2NVersionTableConstant` today; moving it out of the shared
  constants file entirely is cleaner (pairs with the A3 alternative).
- **B10.** BREAK-aware arity guards in the Leios serializers (finding **11**)
  — Leios-side hardening; the root asymmetry disappears with A1.
- **B11.** Single-validation raw CBOR (finding **13**, Leios-side part):
  validate `LeiosRawCbor` once at construction; drop the re-parse and double
  copy in `validatedRawCborBytes`. (Byte-fidelity to the wire still requires
  A1.)
- **B12.** Built-in periodic keep-alive timer in `LeiosNetworkClient`
  (finding **14**).
- **B13.** Drop `BLUEPRINT_CURRENT` and the profile plumbing (finding **15**).
- **B14.** Finding **12**: no code change — the empty-votes guard matches the
  CDDL (`1* vote`); B1 removes its only dangerous consequence.
- **Interim mitigation for finding 1** until A1 lands: reject empty-bitmap
  requests at the `LeiosNetworkClient`/`LeiosTxBitmap` API boundary (the
  client-triggerable case) and document server-sent empty EB maps as a known
  limitation of the prototype client.

### Sequencing

1. **PR-1 (this branch, Leios feature):** all of Part B, plus the A3
   alternative (Leios-local version table). Zero diff to shared code; no
   regression testing burden beyond the new Leios tests and the Musashi smoke
   test.
2. **PR-2 (shared infrastructure):** A1 + A2 (and A3 as originally written,
   if the shared-table route is preferred). Gate on the full regression
   matrix above before release.
3. Finding 1 is only fully fixed once PR-2 lands; until then PR-1 carries the
   interim mitigation and a documented limitation.

## Consequences

- Findings 1–5 are release blockers for any real consumer of
  `LeiosNetworkClient`: each produces a silently dead or corrupted stream that
  applications cannot detect via the public API. All except the finding-1
  root cause are fixable inside Part B.
- The split means the Leios feature PR cannot regress existing products: with
  the A3 alternative it touches no file that existing consumers execute.
  The cost is a temporary, documented limitation (empty chunked maps from the
  server) until the shared PR-2 lands.
- PR-2 (A1 full fix) is the highest-leverage shared change: it resolves the
  finding-1 corruption, makes EB bytes hash-verifiable (finding 13), and
  removes a decode+re-encode from every protocol's receive path — but it is
  exactly the kind of change that demands the multi-epoch Haskell-node sync
  regression before release.
- Finding 10's fix (B9) changes activation behavior for non-Musashi networks
  only; Musashi behavior is unchanged.
- The refuted-findings section documents why `PROTOCOL_V15` in the shared
  version tables is wire-safe, so future reviews need not re-derive it — but
  wire-safe is not regression-tested; A3 keeps that burden in the shared PR.

## Review provenance

- Branch: `feat/leios_protocol_impl` (untracked new files + modified
  `N2NVersionTableConstant.java`), reviewed 2026-07-02.
- Method: workflow-backed multi-agent review, xhigh effort — 6 finder agents
  (independent correctness angles + cleanup), 44 candidates, one independent
  adversarial verifier per distinct (file, line) location; 4 refuted; ranked
  and capped to 15 reported findings (minor duplication findings omitted under
  the cap).
- Key mechanisms were verified by execution where possible (e.g. the cbor-java
  0.9 empty-chunked-map re-encode was reproduced against the exact dependency
  version from `gradle/libs.versions.toml`).
