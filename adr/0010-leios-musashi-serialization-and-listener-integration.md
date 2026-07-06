# ADR 0010: Linear Leios (Musashi) — Serialization & BlockChainDataListener Integration

Date: 2026-07-06

Status: Proposed

## Context

ADR 0007 landed the transport-only Leios mini-protocols (`leios-notify` = 18,
`leios-fetch` = 19) on branch `feat/leios_protocol_impl`
([PR #167](https://github.com/bloxbean/yaci/pull/167)); ADR 0008 recorded the
review findings against it. All Leios payloads currently cross the API boundary
as opaque `LeiosRawCbor`, and the only consumer surface is the standalone
`LeiosNetworkClient` + `LeiosDataListener` (its own TCP connection, raw events).

This ADR designs the next layer: **serialization for the Musashi Leios data
structures (Endorser Blocks, votes, Dijkstra ranking-block extensions) and
integration into the existing sync surface** so that:

1. Apps using `BlockSync` / `BlockRangeSync` (yaci-store above all) keep
   receiving ranking blocks through `BlockChainDataListener.onBlock(era, block,
   txs)` **with zero API change** — on mainnet and on Musashi.
2. New **default** listener methods surface Endorser Blocks and votes to apps
   that opt in, on the same listener and the same connection.
3. The same application code works against mainnet (Leios inactive) and Musashi
   (Leios active), selected by network magic + handshake — not by code changes.
4. yaci-store's changes reduce to: bump yaci, map the new `Era.Dijkstra`
   constant, optionally override the new listener defaults.

Linear Leios is **not final**. This ADR implements exactly what the live
Musashi prototype speaks today and pins where every format is expected to move.

### Sources reviewed (2026-07-06)

- **cardano-blueprint** `leios-prototype`:
  - pinned prototype profile: `188183b37081fa012fa890236edb7771f96ae92f`
    (same pin as ADR 0007)
  - branch head: `3f3f9925b` (2026-07-03) — 8 commits ahead; two material
    drifts recorded below (vote shape, Dijkstra block restructure)
  - `src/network/node-to-node/leios-{notify,fetch}/messages.cddl`,
    `src/ledger/eras/dijkstra.cddl` (prototype branch only),
    `src/network/node-to-node/txsubmission2/tx.cddl`, `src/codecs/base.cddl`
- **CIP-0164** (merged, status Proposed; incl. PRs #1167, #1196): EB / vote /
  certificate / RB-header CDDL (Appendix B), ledger-inclusion semantics,
  timing parameters. Committee model is now stake-truncation (no
  persistent/non-persistent voter split — earlier drafts referenced by ADR
  0003/0004 are outdated on this point).
- **Haskell prototype** (`IntersectMBO/ouroboros-consensus` @ `leios-prototype`,
  `LeiosDemoTypes.hs` et al.) — the node Musashi actually runs
  (`cardano-node 11.0.1.164`, weekly `input-output-hk/ouroboros-leios`
  prototype releases).
- **Yaci**: ADR 0007/0008/0009, `docs/linear-leios-support-plan.md`,
  `docs/leios-spec-tracking.md`, and the current branch code.

### What the live Musashi wire actually is (facts this design builds on)

| Structure | Musashi today (pinned prototype) | Where it's headed |
| :--- | :--- | :--- |
| RB era wrapper | `[8, dijkstra.block]` over BlockFetch; Dijkstra = wire tag **8** (ns8 index 7 for txs) | unchanged |
| RB header | Conway shape (10-item header body) **plus a trailing 11th element `[eb_hash, eb_size]`** once the producer announces an EB; plain 10-item body otherwise (observed on the live network after the Leios header extension activated mid-Dijkstra; the extension must be carried verbatim so header hashes stay stable) | CIP: optional `(announced_eb, announced_eb_size)` group + `certified_eb` bool inside the header body |
| RB body | Conway's 5 segments **plus** `leios_cert / nil`, `peras_cert / nil` — both placeholder `[]` today | blueprint head restructures to `block = [header, block_body]` with nested whole transactions and a real `leios_certificate = [signers bitfield, aggregated BLS sig]`; CIP puts `eb_certificate` in the body with "cert ⇒ no txs" |
| Endorser block | **bare definite-length CBOR map `{ tx_hash(32) => tx_size(word32) }`** (Haskell `encodeMapLen`; hash of the EB = blake2b-256 over these bytes) | CIP: `[ omap<hash32, uint16> ]` — array-wrapped, uint16 sizes |
| Vote (notify tag 4) | pinned: `[slot, eb_hash, voter_id(word16), sig(48)]`; **blueprint head + current Haskell: `[announcing_rb_hash, voter_id, sig(48)]`** (3 elements — votes now sign the announcing RB hash) | CIP: `[slot_no, endorser_block_hash, voter_id(uint), vote_signature]` |
| `tx_list` items | `[eraIdx, #6.24(<<full tx bytes>>)]` (ns8; Dijkstra = 7) — the tag-24 byte string carries the exact full-tx bytes | CIP keeps era-wrapped txs |
| Bitmaps | `{ word16 window => word64 mask }`, **must** encode indefinite-length, MSB-first (prototype peers reject a definite-length map and reset the connection — the constraint ADR 0007 pinned and `LeiosCborUtil` already encodes) | CIP describes a roaring-style flat byte string — different codec, do not unify |
| announcement (notify tag 1) | still `any ; TODO` — believed to be the raw CBOR of the announcing RB header (unconfirmed; open question 4) | CIP folds announcement into the RB header |
| Range/votes fetch | not implemented in prototype (`msgLeios*Range*` commented out) | CIP has multi-EB batch fetch for catch-up |

Implications already verified against Yaci's code:

- `EraUtil.getEra(8)` returns `null` today — this is the **single hard blocker**
  for `onBlock` on Musashi. `BlockSerializer` reads block-array positions 0–4
  with a `size() > 4` guard (`BlockSerializer.java:103`), so the two extra
  trailing Dijkstra items parse-tolerate already; `BlockHeaderSerializer`
  routes by "is the last header-body item an unsigned int" and then uses
  absolute indices 0–9 (`BlockHeaderSerializer.java:32`), so an 11-item
  Dijkstra header body with a trailing `[eb_hash, eb_size]` **array** routes to
  the post-Babbage path and parses — silently dropping the announcement.
- `BlockChainDataListener` is all default methods — additive callbacks are
  binary-compatible with every existing implementor.
- `BlockFetchAgentListenerAdapter.blockFound` (`:63`) is the sole `onBlock`
  call site; `N2NChainSyncFetcher.init()` is the single place the N2N agent
  list is assembled — both are clean seams.
- ADR 0008 finding A1: the inbound mux decode→re-encode is not byte-faithful.
  Until the shared fix lands, `LeiosRawCbor` bytes are *value*-faithful, not
  guaranteed *byte*-faithful — EB-hash verification must be advisory.

## Decision

Add Leios serialization and sync-surface integration in **three additive
layers**, each shippable on its own, all Musashi-profile-pinned:

1. **Core / era continuity** — `Era.Dijkstra` plus shape-tolerant Dijkstra
   header & body parsing, so `onBlock` flows unchanged on Musashi.
2. **Core / Leios domain models** — `model/leios/*` + deserializers that decode
   the (until now opaque) `LeiosRawCbor` payloads: `EndorserBlock`, votes, EB
   transaction lists. The protocol layer stays opaque; decoding happens above
   it.
3. **Helper / listener + orchestration** — new default methods on
   `BlockChainDataListener`, an internal coordinator that drives
   notify→fetch→assemble, and opt-in wiring of the Leios agents into
   `BlockSync` / `BlockRangeSync` on the **same** connection.

Naming note: Yaci already uses "EB" for Byron **epoch-boundary** blocks
(`ByronEbBlock`, `onByronEbBlock`). All new API says **`EndorserBlock`** in
full; nothing Leios-facing is abbreviated "Eb…Block".

### Layer 1 — Dijkstra era & ranking-block continuity (core)

1. **`Era.Dijkstra(8)`** and `EraUtil.getEra`: `case 8 -> Era.Dijkstra`.
   (Matches the wire: the prototype blueprint's blockfetch CDDL defines
   `[8, dijkstra.block]`. Yaci's Era values already mirror wire tags.)
2. **Header announcement extension.** New optional model
   `LeiosAnnouncement { String ebHash; long ebSize; }` as a nullable field on
   `HeaderBody`. In `BlockHeaderSerializer.postBabbageHeader`: after index 9,
   if `headerBodyArr.size() > 10` and item 10 is a 2-element array
   `[bstr(32), uint]`, populate it; any other extra shape → log at debug and
   ignore. Shape-guarded, era-agnostic: Conway headers have exactly 10
   items, so mainnet behavior is byte-identical. Block-hash derivation is
   unaffected (it re-serializes the whole header array, extension included).

   **Routing heuristic must change with it.** Today
   `getBlockHeaderFromHeaderArray` picks pre- vs post-Babbage by the major
   type of the **last** header-body item (`BlockHeaderSerializer.java:32`).
   That happens to work for the current Musashi extension (last item is an
   array) but misroutes as soon as a trailing **uint** appears — exactly the
   CIP direction, where `(announced_eb: hash32, announced_eb_size: uint32)`
   is a spliced group whose last item is a uint: such a header would fall
   into `preBabbageHeader` and fail before any Layer-1 guard runs. PR-A
   replaces the last-item check with a structural discriminator at a fixed
   index — post-Babbage iff item 8 is an Array (the operational-cert array;
   pre-Babbage item 8 is the `block_body_hash` byte string) — which is
   trailing-extension-proof for both the current prototype and the CIP
   shape. Covered by the header fixture matrix.
3. **Body trailing certificates.** Nullable `String leiosCertCbor` /
   `String perasCertCbor` (hex of the raw item) on `Block`, populated from
   positions 5/6 when present and not `nil`. Today these are placeholder
   `[]` — carrying raw bytes is deliberate: when the real certificate shape
   lands (blueprint head already defines `[signers, aggregated_signature]`),
   parsing upgrades without another `Block` shape change.
4. **Parse-error posture.** Dijkstra parsing failures follow the existing
   `BlockParseRuntimeException` → `onParsingError` path; with
   `returnBlockCbor` enabled the raw block survives for diagnosis (the
   support plan's "follow the chain without crashing, preserve raw data").
5. **Compatibility note (precision on "zero API change").** The *listener*
   contract is unchanged and binary-compatible. The *model* classes are
   not frozen: `Block` and `HeaderBody` carry Lombok `@AllArgsConstructor`,
   so the new nullable fields change the generated public all-args
   constructor signatures (and `equals`/`toString`/Jackson output gain the
   fields). This matches existing precedent — `HeaderBody.vrfResult`
   (Babbage) and `Block.cbor` were added the same way — and the supported
   construction path is the builder, but consumers that call the all-args
   constructors directly will need a recompile. Called out in release
   notes rather than worked around.
6. **Header bytes fidelity (correction of a common assumption).**
   `rollforward(...)`'s `originalHeaderBytes` are **not** wire slices:
   `RollForwardSerializer.java:61` re-serializes the parsed
   `wrappedHeader`. The inner header rides as a CBOR byte string, so its
   content bytes survive the value round-trip verbatim (definite bstr
   re-encodes identically); only the outer wrapper framing is re-encoded —
   and the whole path already sits behind the ADR 0008 A1 mux
   decode→re-encode. Net: header-content fidelity is value-level today,
   byte-level only after A1; nothing in this ADR may assume byte-exact
   header bytes before then.

Exit: `BlockSync`/`BlockRangeSync` against a Musashi relay deliver
`onBlock(Era.Dijkstra, block, txs)`; existing era tests byte-identical.

### Layer 2 — Leios domain models & serializers (core)

New packages `core/.../model/leios/` and `core/.../model/serializers/leios/`.
Deserializers take **raw bytes** (from `LeiosRawCbor.getCbor()` or fixtures) —
the `protocol/leios*` layer keeps its opaque boundary from ADR 0007, and the
models are reusable for N2C/fixtures later.

Models (Lombok value style, consistent with `core.model`; every model retains
its raw CBOR hex like `TransactionBody.cbor` does):

- **`EndorserBlock`** — `List<EndorserBlockTxRef> txRefs` (insertion order
  preserved — the order is ledger-semantic), `String cbor`,
  `String computedHash` (blake2b-256 over the raw bytes; see caveat below),
  `int txCount()`, `long totalTxBytes()`.
  `EndorserBlockTxRef { String txHash; long txSize; }`
- **`LeiosVote`** — shape-tolerant union of the three known encodings:
  `LeiosVoteFormat format` (`SLOT_EB_HASH` 4-el pinned, `ANNOUNCING_RB_HASH`
  3-el head, `UNKNOWN` raw-only), nullable `Long slot`, `String ebHash`,
  `String announcingRbHash`, `Integer voterId`, `String voteSignature`,
  `String cbor`. Decoder branches on array arity; anything unrecognized
  degrades to `UNKNOWN` + raw, never throws into the notify loop.
- **`EndorserBlockTx`** — one `tx_list` element: `int index`,
  `int txEraIndex` (the raw ns8 index) plus `Era era` derived via a
  **dedicated ns8 mapper** — NOT `EraUtil.getEra`. The two index spaces
  differ by one: blockfetch era wrappers are `0/1=Byron … 7=Conway,
  8=Dijkstra` (what `EraUtil` maps), while `tx.cddl`'s ns8 is
  `0=Byron … 6=Conway, 7=Dijkstra`. Reusing the block mapper would label a
  Dijkstra tx as Conway and a Conway tx as Babbage. Also `String txCbor`
  (the exact tag-24 inner bytes), `String txHash` (blake2b-256 over those
  bytes — the prototype references txs by full-serialized-bytes hash;
  **verify against live data**, see Verification), plus raw fallback when
  the `[eraIdx, 24(bytes)]` shape doesn't match.
- **`LeiosCertificate`** — placeholder: raw cbor + best-effort
  `[signers, aggregatedSignature]` parse for forward compatibility; not part
  of any listener payload until the network emits real certs.

Serializers (enum singletons, `Serializer<T>` style):

- **`EndorserBlockSerializer`** — accepts **both dialects**: bare CBOR map
  (Musashi today) and array-wrapped omap (CIP / blueprint-head direction),
  definite or indefinite. Decodes via a **streaming/raw map-entry walk, not
  cbor-java's materialized `Map`** — that model is LinkedHashMap-backed, so
  duplicate keys would silently collapse (and corrupt entry count/order)
  before any model-level check could run. The entry walk is what guarantees
  both duplicate-hash rejection and the ledger-semantic insertion order.
  Sizes kept as `long` (word32 today, uint16 in CIP — width from data, not
  from a shared struct).
- **`LeiosVoteSerializer`** — arity-branching as above.
- **`EndorserBlockTxListSerializer`** — unwraps `[ *[eraIdx, 24(bytes)] ]`,
  slicing the inner byte string directly (byte-exact by construction: tag-24
  content is a definite bstr, immune to the A1 re-encode issue).

Explicit non-goals (unchanged from ADR 0007): BLS verification, committee
selection, announcement decoding beyond raw bytes (still `any` in CDDL — we
surface it raw and revisit when pinned), `BLUEPRINT_CURRENT`/CIP wire support.

### Layer 3 — Listener & sync integration (helper)

**New default methods on `BlockChainDataListener`** (binary-compatible; no
existing implementor recompiles):

```java
default void onEndorserBlock(EndorserBlockEvent event) {}
default void onLeiosVotes(LeiosVotesEvent event) {}
```

Event objects (new `helper/.../model/leios/`), not positional parameters —
Leios is still moving, and event carriers let us add fields without breaking
implementors (the lesson from `onBlock`'s frozen signature):

- `EndorserBlockEvent { LeiosPoint point; long announcedEbSize;
  EndorserBlock endorserBlock; List<EndorserBlockTx> transactions;
  boolean txsComplete; String announcementCbor /* nullable, best-effort */; }`
  — `txsComplete=false` when tx fetch was capped/failed/disabled;
  `transactions` empty for tx-ref-only consumers. `announcementCbor` is
  populated only when the coordinator could correlate an announcement to
  this EB (see step 2 below) — consumers must treat it as optional.
- `LeiosVotesEvent { List<LeiosVote> votes; }`

**`LeiosSyncCoordinator`** (helper, package-private): implements
`LeiosNotifyAgentListener` + `LeiosFetchAgentListener` and owns the
notify→fetch→assemble policy:

1. `onBlockOffer(point, ebSize)` → dedupe by `ebHash` (bounded LRU, ~1k
   entries / TTL) → `requestBlock(point)`.
   `onBlockAnnouncement(rawCbor)` → held in a small bounded ring of recent
   announcements; **best-effort** correlation only: if the announcement
   parses as an RB header whose `LeiosAnnouncement` extension matches a
   tracked point's `ebHash`, it is attached to that point's state (and
   surfaces as `announcementCbor`); otherwise it ages out silently — the
   payload is still `any` in the CDDL, so correlation is opportunistic by
   design.
2. Per-point state machine `{ blockOffered, txsOffered, ebFetched }`,
   updated on `onBlockTxsOffer(point)` and `onBlock(point, rawEb)` in
   **either arrival order**. A `requestBlockTxs(point, LeiosTxBitmap.firstN(
   min(txCount, maxTxsPerEndorserBlock)))` is issued only once
   `ebFetched && txsOffered && fetchTxs && txCount > 0` — ADR 0007's
   conservative rule ("request EB txs only after a `BlockTxsOffer` for the
   same `LeiosPoint`") stands: `leios-fetch` has no not-found/error
   response, so requesting an unoffered closure risks a stall or peer
   reset. If the EB is fetched but no txs offer arrives within a bounded
   window (config `txsOfferWaitMillis`) — or `fetchTxs` is off or
   `txCount == 0` — emit refs-only with `txsComplete=false`.
3. `onBlockTxs(...)` → `EndorserBlockTxListSerializer` → assemble → emit
   `onEndorserBlock` on the `BlockChainDataListener`.
4. `onVotes(rawVotes)` → `LeiosVoteSerializer` → `onLeiosVotes` (only when a
   listener overrides it — votes are high-volume; skip parse work otherwise
   via a cheap `isVoteListenerActive` flag on config).
5. Every callback wrapped try/catch (ADR 0008 B3 discipline): a throwing
   application listener must never stall the notify/fetch loops; parse
   failures emit nothing but log point + raw hex at debug.

**Wiring — same connection, opt-in, magic-gated.** New
`LeiosConfig { Mode mode /* AUTO | ENABLED | DISABLED */, boolean fetchTxs,
int maxTxsPerEndorserBlock, long txsOfferWaitMillis, boolean deliverVotes }`,
default `AUTO`:

- `N2NChainSyncFetcher` (→ `BlockSync`) and `BlockFetcher`
  (→ `BlockRangeSync`) accept an optional `LeiosConfig` (new constructor
  overload / setter; existing constructors behave exactly as today with
  `AUTO`).
- **Attachment** (whether the agents are constructed and passed to the
  `TCPNodeClient`) is decided by `mode` + magic; **activation** (whether the
  notify loop starts after the handshake) is decided by a
  **magic-parameterized** compatibility check — *not* the current
  `isLeiosCompatible`, which hard-codes magic 164 and would leave
  `ENABLED`-on-other-networks attached-but-dead. The check becomes:
  negotiated version ≥ V15, non-app-layer, and handshake `versionData`
  magic == the **connection's configured** magic. The Musashi-specific
  `== 164` condition survives only inside `AUTO`'s attachment rule.
- `AUTO` (default): attach only when
  `protocolMagic == MUSASHI_PROTOCOL_MAGIC`, and — for tip-following
  clients (`N2NChainSyncFetcher`/`BlockSync`) only, see the
  `BlockRangeSync` rule below. On mainnet/preprod the agent list is
  byte-identical to today.
- `ENABLED`: always attach (future Leios networks); activation still
  requires the version gate above against the configured magic.
  `DISABLED`: never attach.
- **`BlockRangeSync`/`BlockFetcher` default to no attachment even under
  `AUTO`.** A live notify agent on a historical-range connection would emit
  near-tip EB events unrelated to the requested range — surprising for a
  bounded batch API. `ENABLED` opts in, with the near-tip semantics
  documented (events carry their own points/slots; they are *not* scoped to
  the range). No speculative fetch of EBs referenced by historical header
  announcements either: the prototype has no error response and unknown EB
  retention, so requesting old EBs risks stalls/resets — revisit when the
  CIP batch/range messages exist.
- The version table for Musashi connections is the Leios-local one
  (V11–V15), not a change to the shared `v4AndAbove` (ADR 0008 A3
  alternative).

`LeiosNetworkClient` stays as the standalone raw-access client
(`LeiosDataListener`, opaque payloads) for tooling/diagnostics; the
coordinator is the supported app path. Optionally the coordinator is also
reusable inside `LeiosNetworkClient` later — not required now.

**Delivery semantics (documented on the listener javadoc):**

- `onBlock` is unchanged and remains the only ledger-authoritative stream.
  On today's Musashi, EB transactions are **not ledger-effective** (the
  certificate is a placeholder) — `onEndorserBlock` is observational.
- No ordering guarantee between `onEndorserBlock(e)` and the `onBlock` of
  the announcing RB; correlate via
  `headerBody.leiosAnnouncement.ebHash == event.point.ebHash`. Both carry
  slots, so slot-based rollback/pruning composes.
- Historical catch-up: the prototype has **no** EB range fetch — EB events
  flow near tip only. `BlockRangeSync` over old ranges yields blocks (with
  their header announcements) but no EB bodies, and per the wiring rules
  above it does not even attach the Leios agents unless explicitly
  `ENABLED`. Documented limitation until the CIP batch-fetch messages
  exist.

### yaci-store impact (the point of the exercise)

- Bump yaci. `onBlock` code path: **zero change** (Dijkstra blocks arrive as
  ordinary `Block`s; empty-tx CertRBs don't exist yet on Musashi, and when
  they do they're just blocks with empty tx lists).
- One mechanical addition wherever yaci-store switches on `Era`: the new
  `Dijkstra` constant (compile-time visible, not a behavior change).
- Leios data: opt-in by overriding `onEndorserBlock` / `onLeiosVotes` —
  feeding the `stores:leios` module sketched in
  `docs/linear-leios-support-plan.md` (tables `leios_endorser_block`,
  `leios_eb_tx`, …) whenever that work starts. Nothing in this ADR blocks or
  presupposes it.

## Future-proofing map (what changes when the spec moves)

| Expected change | Blast radius under this design |
| :--- | :--- |
| Vote shape settles (3-el head form or CIP 4-el) | `LeiosVoteSerializer` branch + `LeiosVoteFormat`; no listener/API change |
| Real `leios_certificate` in RB body | upgrade `LeiosCertificate` parse from the already-carried raw bytes; add `onLeiosCertificate` default method if wanted |
| Dijkstra body restructure (blueprint head: `[header, block_body]`, nested whole txs) | Dijkstra-specific branch in `BlockSerializer` dispatch (shape-detect position 1); `Block` model gains nothing — txs still land in `transactionBodies` |
| RB header announcement moves into a CIP optional group | `BlockHeaderSerializer` shape guard extended; `LeiosAnnouncement` model unchanged |
| EB becomes array-wrapped omap with uint16 sizes | already accepted by the dual-dialect `EndorserBlockSerializer` |
| Bitmap becomes roaring-style bytes | new codec beside `LeiosTxBitmap`, selected by profile — per CIP analysis, do **not** unify the codecs |
| CIP 4-mini-protocol split (LeiosAnnounce/Votes/BlockNotify/Fetch) | new agents beside the existing two; coordinator and listener API unchanged |
| Certified EBs become ledger-effective | new opt-in "ledger-effective" merged view (support-plan Phase 3); `onBlock` stays raw-RB by contract |

## Phased plan

1. **PR-A — core era continuity.** `Era.Dijkstra`, `EraUtil`, header
   `LeiosAnnouncement`, body cert raw fields, fixtures + tests. Small,
   additive, but touches shared parsers → runs the existing era regression
   suite plus a mainnet/preprod sync smoke. *Exit:* Musashi blocks flow
   through `onBlock`; Conway fixtures byte-identical.
2. **PR-B — Leios models & serializers.** `model/leios/*`,
   `model/serializers/leios/*`, golden tests from captured fixtures + CDDL
   examples (both EB dialects, 3/4-el votes, tx_list unwrap, empty EB).
   No protocol/helper changes. *Exit:* opaque payloads from PR #167 decode
   offline.
3. **PR-C — helper integration.** Listener methods + events, coordinator,
   `LeiosConfig` wiring in `N2NChainSyncFetcher`/`BlockFetcher`,
   default-disabled Musashi integration test, docs. *Exit:* one `BlockSync`
   against Musashi yields `onBlock` + `onEndorserBlock` on the same
   listener; the same code against mainnet is behaviorally unchanged.
4. **yaci-store validation.** Local Musashi relay run: era mapping, block
   persistence, parser-gap report. (Store-side Leios module is separate work.)

Ordering dependencies: PR #167 (with ADR 0008 Part B fixes) merges first.
ADR 0008's A1 mux byte-fidelity fix (shared PR-2) should land before EB-hash
verification is promoted from advisory to enforced; nothing else here waits
on it.

## Verification

- **Fixture capture first** (start of PR-A): run `LeiosNetworkClient` +
  `BlockSync` (with `returnBlockCbor`) against a Musashi relay; commit hex
  fixtures under `core/src/test/resources/leios/`: Dijkstra header with and
  without announcement, block with `nil`/`[]` certs, ≥3 real EBs (incl. a
  1-tx and a large one), a votes message, a tx_list response. Fixtures pin
  the *actual* wire truth against the CDDL drift documented above — if the
  live vote is already 3-element, the pinned-profile assumption updates
  before code is written.
- **Unit:** Conway header/block fixtures re-run byte-identical (regression);
  Dijkstra header 10-item and 11-item; unknown 12th header item ignored;
  EB map definite/indefinite/empty/array-wrapped/duplicate-hash; vote arity
  3/4/unknown; tx_list tag-24 slicing byte-exact (hash matches EB tx ref);
  coordinator: offer→fetch→assemble happy path, dedupe, capped txs
  (`txsComplete=false`), throwing app listener doesn't stall the loop.
- **Cross-checks:** `EndorserBlock.computedHash == point.ebHash` asserted on
  fixtures (raw file bytes — valid regardless of A1); at runtime it's a
  debug-level warn until A1 lands. `EndorserBlockTx.txHash` membership in
  the EB's tx-ref set — this empirically settles the full-bytes-vs-body
  hash question; if it fails, the tx-ref hash semantics get re-derived from
  the Haskell source before PR-B merges.
- **Integration (default-disabled):** Musashi relay — sync N blocks:
  ≥1 `onBlock` with `Era.Dijkstra`, ≥1 `onEndorserBlock` with
  `txsComplete=true`, and ≥1 header announcement matching a received EB
  point. Mainnet relay — identical agent list and behavior with the same
  code and `AUTO` config.
- **Regression:** full existing test suite; `test-haskell-sync` unaffected
  (no shared-protocol changes in PR-B/C; PR-A parser changes are covered by
  era fixtures).

## Risks

| Risk | Mitigation |
| :--- | :--- |
| Live wire ≠ pinned CDDL (vote already drifted at head; testnet redeploys weekly) | Fixture capture precedes code; shape-tolerant decoders degrade to raw + `UNKNOWN`, never throw into protocol loops; profile pins recorded here and in `docs/leios-spec-tracking.md` |
| Dijkstra body restructure ships mid-stream (blueprint head already has it) | Layer-1 parser is position-guarded; restructure detection is a planned dispatch branch, and `onParsingError` + raw CBOR keep the failure diagnosable |
| Header shape guard misfires on a future non-Leios header change | guard requires exactly `[bstr(32), uint]` at index 10; anything else is ignored, never fatal |
| A1 mux re-encode makes `LeiosRawCbor` non-byte-exact | hash checks advisory until A1; tag-24 tx bytes are immune (definite bstr); fixtures use raw file bytes |
| Vote volume overwhelms listeners | parse + dispatch only when `deliverVotes` and an override exist; votes never buffered |
| EB tx-ref hash semantics wrong (full-bytes vs body hash) | settled empirically by the cross-check before PR-B merges |
| Coordinator bugs stall sync | coordinator is listener-side only — ChainSync/BlockFetch never wait on it; failures degrade to "no EB events", `onBlock` unaffected |
| API leaks prototype shapes into stable surface | event carriers + `@ApiStatus.Experimental`-style javadoc marking; `LeiosVoteFormat`/dialect enums make prototype-ness explicit |

## Open questions

1. Does the live Musashi vote match the pin (4-el) or head (3-el)? (Fixture
   capture answers; decoder handles both either way.)
2. Are EB tx-ref hashes over full tx bytes or tx body bytes? (Cross-check
   answers.)
3. Should `onEndorserBlock` fire for EBs whose txs were not fetched
   (`fetchTxs=false`) immediately, or wait? (Proposed: fire immediately with
   `txsComplete=false`; revisit with usage.)
4. Exact `announcement` payload contents (still `any` in CDDL; believed to
   be the announcing RB header — confirm from the Haskell prototype source
   or live traffic) — surfaced raw until pinned.
5. When Musashi redeploys with the restructured Dijkstra block, does the era
   wire tag stay 8? (Assumed yes; fixtures re-captured on each testnet
   repin.)

## Acceptance criteria

- `BlockSync`/`BlockRangeSync` follow Musashi with `onBlock(Era.Dijkstra, …)`
  and no API change; mainnet behavior byte-identical with the same jar.
- `EndorserBlock`, `LeiosVote`, `EndorserBlockTx` decode all captured live
  fixtures; both EB dialects and both vote arities covered by tests.
- New listener methods are default no-ops; yaci-store compiles against the
  new yaci with only the `Era.Dijkstra` mapping addition.
- EB-hash and tx-hash cross-checks pass on fixtures.
- No changes to `protocol/leios*` message/serializer contracts (ADR 0007's
  opaque boundary intact); no shared-protocol hot-path changes outside PR-A's
  guarded parser extensions.

## Key files / new packages

**New:** `core/.../model/leios/{EndorserBlock,EndorserBlockTxRef,EndorserBlockTx,LeiosVote,LeiosVoteFormat,LeiosCertificate,LeiosAnnouncement}.java`;
`core/.../model/serializers/leios/{EndorserBlockSerializer,LeiosVoteSerializer,EndorserBlockTxListSerializer}.java`;
`helper/.../model/leios/{EndorserBlockEvent,LeiosVotesEvent}.java`;
`helper/.../LeiosSyncCoordinator.java`; `helper/.../LeiosConfig.java`;
`core/src/test/resources/leios/*` fixtures.
**Touched:** `core/.../model/Era.java` (+`Dijkstra(8)`),
`core/.../common/EraUtil.java`, `core/.../model/serializers/BlockHeaderSerializer.java`
(guarded index-10 extension), `core/.../model/serializers/BlockSerializer.java`
(guarded positions 5/6), `core/.../model/{HeaderBody,Block}.java` (nullable fields),
`helper/.../listener/BlockChainDataListener.java` (2 default methods),
`helper/.../{N2NChainSyncFetcher,BlockFetcher,BlockSync,BlockRangeSync}.java`
(optional `LeiosConfig` wiring).
**Unchanged by contract:** `core/.../protocol/leios*/**` (transport),
`LeiosNetworkClient` (standalone raw client).
