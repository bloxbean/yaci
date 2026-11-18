# Leios / Musashi: Source Tracking & Change-Handling Guide

Last verified: 2026-07-07

This is the operational guide for anyone maintaining Yaci's Linear Leios
(CIP-0164) support against the **Musashi** prototype testnet: where the wire
formats are defined, how releases roll out, which repositories to watch, and
the exact procedure to follow when the network changes.

Companion documents:

- `docs/leios/leios-spec-tracking.md` — the current pin matrix (what Yaci
  implements right now, at which commit).
- `docs/leios/linear-leios-support-plan.md` — the long-term Yaci / Yaci Store plan.
- `adr/0007`, `adr/0010`, `adr/0011` — design records for the mini-protocols,
  serialization/listener integration, and the w27 block restructure.

---

## 1. The one-paragraph mental model

Linear Leios is specified by **CIP-0164** (merged, status *Proposed*), but the
Musashi testnet does **not** speak CIP-0164 — it speaks a weekly-moving
**prototype dialect**. The prototype's wire truth lives on `leios-prototype`
branches across four repos, flows downstream in a fixed direction, and hits
the live network only when a **`prototype-2026wXX` release** ships. Breaking
releases **respin the network** (chain reset, state deletion), so "Musashi
support" is only ever true *per release tag*, never open-ended.

```
IntersectMBO/cardano-ledger @ leios-prototype          (ledger types, Haskell)
        │  generate-cddl (auto-generated CDDL)
        ▼
cardano-scaling/cardano-blueprint @ leios-prototype    (SPEC OF RECORD)
        │  src/ledger/eras/dijkstra.cddl        ← auto-synced ledger CDDL
        │  src/network/node-to-node/leios-*/    ← hand-maintained protocol CDDL
        ▼
IntersectMBO/{ouroboros-consensus,ouroboros-network,cardano-node}
        @ leios-prototype                              (the node implementation)
        ▼
input-output-hk/ouroboros-leios  →  prototype-2026wXX releases
        ▼
Musashi network respin (magic 164)                     (the wire truth)
```

---

## 2. Repositories: what to watch, and for what

| Repo / branch | What it defines | What to watch |
| :--- | :--- | :--- |
| [`input-output-hk/ouroboros-leios`](https://github.com/input-output-hk/ouroboros-leios) — **Releases** | Weekly `prototype-2026wXX` tags; each bundles the node binaries/docker image Musashi runs | **This is the trigger.** Release notes call out breaking wire changes and network respins explicitly (e.g. w24 "breaking N2N wire format change", w27 "breaking changes to block serialization... requires network respin"). Subscribe to releases. |
| [`cardano-scaling/cardano-blueprint`](https://github.com/cardano-scaling/cardano-blueprint) @ `leios-prototype` | **Spec of record** for the prototype: `src/network/node-to-node/leios-notify/messages.cddl`, `.../leios-fetch/messages.cddl` (hand-maintained), `src/ledger/eras/dijkstra.cddl` (auto-generated) | Commits touching those three files. "Sync ledger CDDLs from cardano-ledger@leios-prototype" commits signal block-format movement. Compare against our pinned commit before every re-pin. |
| [`IntersectMBO/cardano-ledger`](https://github.com/IntersectMBO/cardano-ledger) @ `leios-prototype` | Source of truth for block/tx/cert CDDL (blueprint syncs from it) | Only when the blueprint sync lags or a CDDL comment needs its origin checked. |
| [`IntersectMBO/ouroboros-consensus`](https://github.com/IntersectMBO/ouroboros-consensus) @ `leios-prototype` | The node's actual encoders (`LeiosDemoTypes.hs`, `LeiosDemoOnlyTestNotify/Fetch.hs`) — mini-protocol ids 18/19, EB encoding | When the CDDL says `any`/TODO (e.g. the `announcement` payload) or a live byte doesn't match the CDDL — the Haskell source settles what the wire really is. |
| [`cardano-foundation/CIPs`](https://github.com/cardano-foundation/CIPs) — CIP-0164 | The **durable target** (final structures: EB omap, 4-field votes, certificates in RB body, four mini-protocols) | Amendment PRs. Do **not** implement CIP shapes against Musashi — the dialects differ (vote shape, tx-size width, bitmap encoding). Prototype and CIP decoders stay separate paths. |
| [leios.cardano-scaling.org](https://leios.cardano-scaling.org/docs/testnet/getting-started/) + [musashi.network](https://www.musashi.network/) | Testnet operations: bootstrap relay, configs, reset announcements | Getting-started/config repins after each respin. |

Key network facts (stable so far): network magic **164**, bootstrap relay
`leios-node.play.dev.cardano.org:3001`, Dijkstra era (block wire tag **8**,
protocol major version 12), N2N handshake version **≥ 15**, mini-protocols
`leios-notify` = **18**, `leios-fetch` = **19**.

---

## 3. Release cadence and what "breaking" means

- Releases are tagged `prototype-2026wXX` (ISO week), roughly weekly, from
  `input-output-hk/ouroboros-leios`. Node version strings carry a `.164`
  suffix (e.g. `cardano-node 11.0.1.164`).
- Two kinds of release:
  - **Non-breaking** (e.g. w25, w26): fixes/CLI only, wire format unchanged.
    No Yaci action beyond a smoke check.
  - **Breaking** (e.g. w24: vote signatures; **w27: block serialization**):
    the release notes say so, and the network is **respun** — chain history
    is deleted and restarts from slot 0 in the new format. Old fixtures
    describe a network that no longer exists (keep them; they document the
    prior pin), and Yaci parsing may fail until re-pinned.
- History of pins Yaci has tracked:

| Yaci state | Blueprint pin | Node release | Notes |
| :--- | :--- | :--- | :--- |
| ADR 0007 (mini-protocols) + ADR 0010 (serialization) | `188183b` (2026-06-29) | w25/w26 | segmented Dijkstra body, placeholder certs, trailing `[eb_hash, eb_size]` header extension, 4-element votes |
| ADR 0011 (current target) | `93276ab` sync / head `cb1de23` (2026-07-06) | **w27** | `block = [header, block_body]` with nested whole transactions, real `leios_certificate`, 12-item header (`leios_certified` bool + `leios_announcement/nil`), 3-element votes |

---

## 4. Where each wire format is defined (cheat sheet)

| Structure | File (blueprint `leios-prototype`) | Notes |
| :--- | :--- | :--- |
| leios-notify messages (tags 0–5), vote | `src/network/node-to-node/leios-notify/messages.cddl` | vote shape has already changed twice — decode arity-tolerantly |
| leios-fetch messages (tags 0–3, 9), EB body map, bitmaps | `src/network/node-to-node/leios-fetch/messages.cddl` | bitmaps **must** encode as indefinite-length CBOR maps (peers reset on definite); range messages still commented out |
| Dijkstra block / header / tx / certificates | `src/ledger/eras/dijkstra.cddl` | auto-generated — never hand-read stale copies, re-fetch at the pin |
| Era-wrapped tx (`tx_list` items) | `src/network/node-to-node/txsubmission2/tx.cddl` | ns8 telescope: **tx era index 7 = Dijkstra** (block wire tag is 8 — the two index spaces differ by one; see ADR 0010) |
| Base scalar types | `src/codecs/base.cddl` | `slotno = word64`, `hash = bstr .size 32`, etc. |
| Block wire wrapper | `src/network/node-to-node/blockfetch/block.cddl` | `[8, dijkstra.block]` |

CDDL trust order when something doesn't parse: **live bytes > Haskell node
source > blueprint CDDL > CIP-0164**. The blueprint is normally enough, but
`any`/TODO fields (e.g. `announcement`) and generation lag mean the node
source and captured fixtures are the tiebreakers.

---

## 5. Procedure: handling a new `prototype-2026wXX` release

Run through this checklist for **every** release (15 minutes for
non-breaking; a re-pin cycle for breaking):

1. **Read the release notes** on `input-output-hk/ouroboros-leios`.
   No wire/serialization changes mentioned → run step 6 as a smoke check and
   stop.
2. **Diff the blueprint** `leios-prototype` branch against our pinned commit
   for the files in §4 (`git log --oneline <ourpin>..origin/leios-prototype
   -- src/ledger/eras/dijkstra.cddl src/network/node-to-node/leios-notify
   src/network/node-to-node/leios-fetch`). Record the new head/sync commits.
3. **Classify the delta** against what Yaci implements (the delta table
   pattern in ADR 0011 §"Delta vs. what the branch implements" is the
   template): parse-failure / silently-dropped-field / already-tolerated /
   not-implemented.
4. **Capture fixtures from the respun network** *before* writing code:
   run `LeiosNetworkClient` and `BlockSync` (with `returnBlockCbor`) against
   the bootstrap relay; save hex fixtures (blocks with/without txs,
   certified blocks, headers with/without announcement, ≥3 EBs, a votes
   message, a tx-list response) under `core/src/test/resources/leios/wXX/`.
   Fixtures pin the *actual* wire truth — the CDDL has drifted from the wire
   before.
5. **Implement behind the era/profile boundary**: Dijkstra-only code paths,
   shape-guarded parsing (unknown shapes → ignore or raw-degrade, never
   fatal on shared paths), raw-CBOR retention for anything still moving.
   Mainnet paths must stay byte-identical — prove it with the Conway
   regression fixtures.
6. **Verify end-to-end**: `YACI_MUSASHI_E2E=true ./gradlew
   :helper:integrationTest --tests '*MusashiBlockSyncE2EIT*'` — zero parse
   errors, minimum Dijkstra RB / Endorser Block counts met.
7. **Update the paper trail**: pin table in `docs/leios/leios-spec-tracking.md`
   (commit hashes + release tag + date), an ADR if the change is
   architectural (new structures, new protocol semantics), and the table in
   §3 above.

### Design rules that keep changes cheap (learned, not aspirational)

- **Pin by commit, trigger by release.** Blueprint commits tell you change
  is coming; only a release makes it real on the wire.
- **Fixtures before code.** Every format assumption gets settled by captured
  bytes, not by reading CDDL alone.
- **Decode tolerantly, emit conservatively.** Arity-branch on shapes that
  have already moved (votes), accept both dialects where cheap (EB map),
  degrade to raw + `UNKNOWN` instead of throwing into protocol loops.
- **Keep raw CBOR on every Leios model.** When the shape changes, consumers
  that stored raw bytes survive; parsed-only consumers don't.
- **Never let prototype shapes leak into stable API contracts.** Event
  carrier objects (not positional listener params), `Experimental` javadoc
  marking, no schema guarantees downstream (yaci-store) based on a
  prototype pin.
- **One spec snapshot per Yaci release.** No runtime profile enum while the
  protocol moves; if two live networks ever speak different dialects,
  prefer lenient decoding + explicit network/capability flags (see
  `docs/leios/leios-spec-tracking.md`).

---

## 6. When does this guide expire?

When Leios reaches release-candidate status and formats converge on
CIP-0164 (+ amendments): the CDDL moves from `leios-prototype` branches into
mainline cardano-ledger/blueprint, network respins stop, and Musashi behavior
graduates from "per-release-tag support" to normal era support (like Conway).
At that point, fold what's still true into `docs/leios/linear-leios-support-plan.md`
Phase 6 and mark this guide historical.
