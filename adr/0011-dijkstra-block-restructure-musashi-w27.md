# ADR 0011: Dijkstra Block Restructure (prototype-2026w27) — Parser Gap & Re-Pin

Date: 2026-07-07

Status: Proposed

## Context

The ADR 0010 implementation (Dijkstra era continuity + Leios serialization +
listener integration) passes its unit suite and review, but a live probe
(`MusashiBlockSyncE2EIT`) against the public Musashi relay fails to parse
current Dijkstra blocks. This ADR records **why**, pins the **new** wire
format, and specifies the parser remediation.

### Why the gap exists

The gap is spec velocity, not an implementation bug:

1. **Yaci pinned deliberately.** ADR 0007/0010 pinned the Musashi profile to
   cardano-blueprint `leios-prototype` @ `188183b` (2026-06-29) — the pin
   discipline both ADRs prescribe for a moving prototype spec.
2. **The ledger restructured the Dijkstra block upstream.** Between
   2026-07-02 and 2026-07-06, `cardano-ledger@leios-prototype` reshaped the
   Dijkstra block (nested whole transactions, a real Leios certificate,
   fixed header slots for the Leios announcement), and cardano-blueprint
   synced those CDDLs (`815693b` 07-02, `64a800a` 07-03, **`93276ab`
   07-06**). ADR 0010's future-proofing map listed this exact restructure as
   in-flight drift.
3. **The network respun onto it.** `input-output-hk/ouroboros-leios` release
   **`prototype-2026w27` (2026-07-06)** ships "breaking changes to block
   serialization" (new Leios certificates and EB announcement encoding) and
   **required a network respin with state deletion**. The chain we developed
   and tested against through w25/w26 no longer exists; every block the
   public relay serves today uses the new format.
4. **Yaci parses the old shape.** `BlockSerializer` reads the pinned
   Conway-style segment layout; on a w27 block, position 1 of the block
   array is now the nested `block_body` (not the tx-bodies segment), so
   segment extraction throws and surfaces as `onParsingError` — exactly what
   the E2E probe reports.

Why upstream restructured (from the CDDL's own comments): the old body's two
trailing nullable placeholder certs could not be wire-disambiguated as
trailing optionals; Dijkstra deprecates the per-tx `is_valid` flag (stripped
once a tx enters a block — `transaction_mempool` rule); and whole nested
transactions make a transaction a single contiguous unit — which also makes
byte-exact full-tx extraction trivial (cf. ADR 0009, whose segment-splicing
problem does not exist in this layout).

### Is there an official CDDL for Musashi?

Yes — with the caveat that it is a **moving branch, versioned only by
commit + weekly node release**, never by a stable spec release:

| Source | Role | Current pin (2026-07-07) |
| :--- | :--- | :--- |
| `cardano-scaling/cardano-blueprint` @ `leios-prototype` | **Spec of record** for the prototype network: hand-maintained mini-protocol CDDLs (`src/network/node-to-node/leios-{notify,fetch}/messages.cddl`) + auto-generated ledger era CDDLs (`src/ledger/eras/dijkstra.cddl`) | branch head `cb1de23` (2026-07-06); dijkstra.cddl last synced by `93276ab` (2026-07-06) |
| `IntersectMBO/cardano-ledger` @ `leios-prototype` | Source of truth behind the ledger CDDLs (auto-generated from the Haskell types via `generate-cddl`; the blueprint syncs from it) | as synced by `93276ab` |
| `IntersectMBO/{ouroboros-consensus,ouroboros-network,cardano-node}` @ `leios-prototype` | The node Musashi runs; EB/mini-protocol encodings in code | as released in w27 |
| `input-output-hk/ouroboros-leios` releases (`prototype-2026wXX`) | The deployment cadence — each release ≈ one blueprint/ledger state; **breaking releases respin the network** | `prototype-2026w27` (2026-07-06) |

Operational rule this ADR adopts: **a Musashi re-pin is triggered by each
`prototype-2026wXX` release**, not by blueprint commits — releases are what
actually hit the wire.

## The two wire shapes (verbatim)

### Old pin — `188183b` (what Yaci currently parses)

```cddl
block =
  [ header
  , transaction_bodies       : [* transaction_body]
  , transaction_witness_sets : [* transaction_witness_set]
  , auxiliary_data_set       : {* transaction_index => auxiliary_data}
  , invalid_transactions     : [* transaction_index]
  , leios_cert               : leios_cert/ nil     ; placeholder: leios_cert = []
  , peras_cert               : peras_cert/ nil     ; placeholder: peras_cert = []
  ]
```

Header: Conway 10-item `header_body`, with the live network appending a
trailing 11th element `[eb_hash, eb_size]` once a producer announced an EB
(the shape ADR 0010 Layer 1 guards for at index 10).

### New pin — `93276ab` / prototype-2026w27 (what Musashi serves now)

```cddl
block = [header, block_body]

; Note that every transaction_index must be strictly smaller than the
; length of transaction_bodies
block_body =
  [ invalid_transactions : invalid_transactions/ nil
  , transactions      : [* transaction]
  , leios_certificate : leios_certificate/ nil
  , peras_certificate : peras_certificate/ nil
  ]

invalid_transactions = nonempty_set<transaction_index>
nonempty_set<a0> = #6.258([+ a0])/ [+ a0]
transaction_index = uint .size 2

transaction = [transaction_body, transaction_witness_set, auxiliary_data/ nil]

; In Dijkstra we're deprecating the `is_valid` flag ... Once the transaction
; is added to a block, the flag will be stripped, so the `is_valid` flag
; cannot appear in transactions that are in a block.
transaction_mempool =
  transaction
  / [transaction_body, transaction_witness_set, true, auxiliary_data/ nil]

leios_certificate =
  [ signers              : bytes           ;bitfield
  , aggregated_signature : leios_signature ; bytes .size 48
  ]
peras_certificate = bytes
```

```cddl
header = [header_body, body_signature : kes_signature]

header_body =
  [ block_number       : block_number
  , slot               : slot
  , prev_hash          : hash32/ nil
  , issuer_vkey        : vkey
  , vrf_vkey           : vrf_vkey
  , vrf_result         : vrf_cert
  , block_body_size    : uint .size 4
  , block_body_hash    : hash32                   ; merkle triple root
  , operational_cert
  , protocol_version
  , leios_certified    : bool                     ; certifies the previous announcement
  , leios_announcement : leios_announcement / nil ; optional EB announcement
  ]

leios_announcement = [announced_eb : hash32, announced_eb_size : uint .size 4]
```

The mini-protocol side (`leios-notify`/`leios-fetch` messages, EB body map,
bitmaps) is **unchanged** at head vs. what the branch implements. The
3-element vote `[announcing_rb_hash, voter_id, vote_signature]` — which the
arity-tolerant `LeiosVoteSerializer` already decodes — is the w27 shape
**per the pinned CDDL and the node source** (`LeiosVote` encodes
`encodeListLen 3` on the `leios-prototype` consensus branch); it has not yet
been observed in captured traffic (live probes to date saw no vote batches),
so the fixture capture below includes a votes message to close that gap.

### Delta vs. what the branch implements

| Piece | Implemented (old pin) | w27 reality | Effect today |
| :--- | :--- | :--- | :--- |
| Block body | 5 Conway segments + 2 trailing nullable placeholder certs | `[header, block_body]`, nested whole 3-element transactions, `invalid_transactions` moved to front (tag-258 set / nil), real certificate | **Parse failure** — the merge blocker |
| Header Leios fields | optional trailing 11th item `[eb_hash, eb_size]` (array shape-guard at index 10) | 12 fixed items: index 10 `leios_certified : bool`, index 11 `leios_announcement / nil` | **No crash** (index-10 guard sees a bool, ignores it; routing discriminator at item 8 still correct) but announcement + certified flag are silently dropped |
| `is_valid` / invalid txs | `invalid_transactions` segment at position 4 | stripped `is_valid`; `nonempty_set<transaction_index>/nil` at body position 0 | needs mapping (incl. tag 258) |
| Leios certificate | raw placeholder `[]` captured as hex | real `[signers bitfield, 48-byte aggregated BLS sig]` | upgrade parse from already-carried raw bytes (as ADR 0010's future map planned) |
| Votes | 4-el pinned + 3-el head, arity-tolerant | 3-el per w27 CDDL + node source (no live capture yet) | no change needed |
| EB / bitmaps / notify / fetch envelopes | per `188183b` | unchanged at head | no change needed |

## Decision

Re-pin the Musashi profile to **blueprint `93276ab` / `prototype-2026w27`**
and implement the new Dijkstra shapes, keeping every change behind the
`Era.Dijkstra` branch so mainnet paths stay byte-identical.

1. **Body parser dispatch.** In `BlockSerializer.deserializeBlock`, when
   `era == Era.Dijkstra` **and** the **inner** block array — element 1 of
   the era-wrapped `[era, block]` pair, i.e. `blockArray` at
   `BlockSerializer.java:43`, NOT the outer pair, which is always 2 items —
   has exactly 2 items with an Array at position 1 (`[header, block_body]`;
   the old segmented layout has 7), route to a new
   `DijkstraBlockBodySerializer`. Inside it, `block_body` must have **at
   least 4 items** (positions 0–3 parsed as pinned; extra trailing items
   tolerated with a debug log, so a future w-release appending a field
   degrades gracefully; fewer than 4 → parse error → `onParsingError`).
   All other eras take the existing path untouched.
2. **Map nested transactions into the existing `Block` model** — this is
   what keeps `onBlock` consumers (yaci-store) unchanged:
   - per `transaction` element: `transaction_body` → `TransactionBody`,
     `transaction_witness_set` → `Witnesses`, `auxiliary_data/nil` →
     `AuxData` keyed by tx index in `auxiliaryDataMap`;
   - `invalid_transactions` (position 0, `nil` | plain array | tag-258
     wrapped array) → `Block.invalidTransactions`;
   - `Block.transactionBodies` order = `transactions` order (ledger order).
3. **Byte-exact extraction, Dijkstra path.** A new
   `DijkstraTransactionExtractor` walks the raw block bytes to each nested
   transaction and slices: exact `transaction_body` bytes (tx-hash
   correctness — same guarantee `TransactionBodyExtractor` provides today),
   exact witness-set bytes, exact `auxiliary_data` bytes. **The existing
   block-level witness extraction does not apply to Dijkstra and must not
   run on it**: `WitnessUtil.getWitnessRawData` /
   `BlockSerializer.handleWitnessDatumRedeemer` walk block position 2 (the
   witness segment), which no longer exists — left in place they would
   throw into the existing catch-and-continue guard and silently ship
   re-encoded datum/redeemer values, reintroducing issue #37 for Dijkstra.
   The Dijkstra path instead feeds the datum/redeemer raw-bytes fix-up from
   the per-transaction witness slices, preserving the same byte-exactness
   guarantee. Because a Dijkstra transaction is contiguous, full-tx CBOR
   (ADR 0009) reduces to slicing the whole 3-element `transaction` item —
   no splicing, no synthesized envelope; the ADR 0009 assembler is bypassed
   for Dijkstra (its 4-element `is_valid` form must NOT be emitted: the
   flag is stripped in blocks by rule).
4. **Real certificate parse — explicit `Block` model decision.** The
   ADR 0010 placeholder fields `Block.leiosCertCbor` / `Block.perasCertCbor`
   (raw hex `String`s) are **replaced**, not kept alongside:
   - body position 2 → new nullable field
     `LeiosCertificate leiosCertificate` on `Block`; the model carries the
     parsed `signers` bitfield + `aggregatedSignature` (48-byte enforced)
     **and** its raw CBOR hex, sliced from the block bytes, not re-encoded —
     resolving the prior review finding at the moment it starts to matter;
   - body position 3 → nullable `String perasCertCbor` stays raw (it is
     plain `bytes` in the CDDL; nothing to parse yet).
   Replacement is safe because no released Yaci version carries the
   placeholder fields. Compatibility note (same as ADR 0010 Layer 1):
   `Block` uses Lombok `@AllArgsConstructor`, so the field swap changes the
   generated all-args constructor / `equals` / `toString` / Jackson shape;
   the builder remains the supported construction path and `onBlock`
   consumers that only read transactions/header see no behavioral change.
5. **Header items 10/11.** Extend the index-guarded parsing in
   `BlockHeaderSerializer.postBabbageHeader`: if item 10 is a boolean →
   `HeaderBody.leiosCertified` (new nullable `Boolean`); if item 11 is a
   2-element `[bstr(32), uint]` array → existing `LeiosAnnouncement`; item
   11 `nil` → absent. The old trailing-array-at-10 guard is kept (harmless,
   recognizes pre-w27 fixtures); unknown shapes are still ignored, never
   fatal. The pre-/post-Babbage routing discriminator (item 8 is an Array)
   is unaffected. Conway headers (exactly 10 items) parse byte-identically.
   Compatibility note: `HeaderBody` also uses Lombok `@AllArgsConstructor`,
   so adding `leiosCertified` changes its generated all-args constructor /
   `equals` / `toString` / Jackson shape for **all** headers, same as the
   `Block` note in Decision 4 (and the `LeiosAnnouncement` field ADR 0010
   already added); the builder remains the supported construction path.
6. **Coordinator/listener layer: no change.** EB fetch, events, votes, and
   `announcementCbor` correlation (which reads `LeiosAnnouncement` from a
   parsed header) work as-is once the header populates the new fields; the
   `leios_certified` flag simply becomes available on every Dijkstra
   `HeaderBody`.
7. **Process: re-pin checklist.** On every `prototype-2026wXX` release:
   check release notes for wire changes; if breaking, re-capture fixtures
   from the respun network, update the pin table in
   `docs/leios/leios-spec-tracking.md` and this ADR series, and run
   `MusashiBlockSyncE2EIT` before claiming support. Musashi support is
   declared **per release tag**, never open-ended.

### Out of scope

BLS verification of the certificate; `block_body_hash` ("merkle triple
root") recomputation/verification; Peras semantics; CIP-0164 final shapes
(separate profile per ADR 0010); N2C/local-client Dijkstra queries.

## Verification

- **Fixtures first**: capture from the respun w27 network — a Dijkstra block
  with txs, an empty block, a block with `leios_certificate` present
  (certified announcement), headers with `leios_announcement` present/nil
  and `leios_certified` true/false; commit under
  `core/src/test/resources/leios/w27/`.
- **Unit**: nested-tx mapping (bodies/witnesses/aux/invalid incl. tag-258
  and `nil`); tx-hash equality between sliced body bytes and a blake2b-256
  recomputation (hashing is era-agnostic; cardano-client-lib's `Blake2bUtil`
  is fine for this); full-tx slice verified by **byte-identity against the
  original block bytes plus a round-trip through Yaci's own Dijkstra
  transaction parser** — NOT by cardano-client-lib `Transaction.deserialize`:
  CCL (through at least 0.8.0-pre4/master) hard-assumes the transaction
  array carries `is_valid` at index 2 and auxiliary data at index 3, so the
  Dijkstra 3-element `[body, witness_set, aux/nil]` form cannot parse
  through it until CCL gains Dijkstra support (any CCL cross-check is
  optional, expected-to-fail, and must not gate the build); certificate
  parse + raw-slice byte equality; header items 10/11 matrix (bool+array,
  bool+nil, old 11-item trailing array, Conway 10-item byte-identical);
  Conway/Babbage regression suite unchanged.
- **E2E**: `MusashiBlockSyncE2EIT` green against the public relay — zero
  parse errors, ≥ configured Dijkstra RB / EB minimums; `onBlock` delivers
  Dijkstra blocks with populated transactions.
- **Cross-check** (chain-level, two headers — NOT a same-header rule): per
  the CDDL comment (`leios_certified : bool ; whether the block certifies
  the previous announcement`) and CIP-0164 (only the immediate child RB can
  certify an announced EB), an RB with `leios_certified == true` implies
  the **immediately preceding RB's** header carries a `leios_announcement`.
  The certifying RB's own `leios_announcement` may be present or `nil` —
  the two header fields are independent within one header. Assert the
  parent-implication on synced ranges (advisory log, never fatal — this is
  a prototype invariant check, not a parse condition).

## Consequences

- Musashi support becomes real again, and the pinned-profile discipline
  gains an explicit trigger (release tag) instead of an implicit one.
- The Dijkstra path is fully isolated behind the era branch; mainnet parsing
  is untouched — the only shared-file edits are the era-dispatch line in
  `BlockSerializer` and the index-guarded header items, both covered by the
  Conway byte-identity regression.
- ADR 0009's splicing machinery is not needed for Dijkstra (contiguous
  transactions); it remains authoritative for Shelley–Conway.
- The placeholder-cert raw fields from ADR 0010 Layer 1 are superseded by
  parsed-certificate + raw-slice fields; no released API carried the
  placeholders, so nothing deprecates.
- Weekly drift risk remains until formats converge on CIP-0164; the re-pin
  checklist and per-release support statement are the mitigation, not a fix.

## Key files

**New:** `core/.../model/serializers/DijkstraBlockBodySerializer.java` (or a
clearly-scoped branch inside `BlockSerializer`),
`core/.../model/serializers/util/DijkstraTransactionExtractor.java`,
`core/src/test/resources/leios/w27/*` fixtures.
**Touched:** `core/.../model/serializers/BlockSerializer.java` (era
dispatch), `core/.../model/serializers/BlockHeaderSerializer.java` (items
10/11), `core/.../model/HeaderBody.java` (`leiosCertified`),
`core/.../model/Block.java` (`leiosCertCbor` replaced by
`LeiosCertificate leiosCertificate`; raw `perasCertCbor` retained; see Decision 4),
`core/.../model/leios/LeiosCertificate.java` (real parse),
`docs/leios/leios-spec-tracking.md` (pin table).
**Unchanged:** `protocol/leios*/**`, `LeiosSyncCoordinator`, listener API,
all non-Dijkstra parsing.
