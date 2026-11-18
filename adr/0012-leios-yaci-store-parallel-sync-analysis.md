# ADR 0012: Leios × Yaci-Store Parallel Initial Sync — Endorser Block Transactions in the Batch Pipeline

Date: 2026-07-07

Status: Proposed

Scope: cross-repo — Yaci (this repo: helper API guarantees, EB range resolution)
and yaci-store (`/Users/satya/work/bloxbean/yaci-store`: pipeline, schema,
processors). yaci-store implementation details here are design direction; the
store repo should carry its own implementing ADR referencing this one.

## Context

### How yaci-store syncs today (verified against the code)

Initial sync and tip sync are two different machines
(`StartService.start()`, `components/core/.../StartService.java:150-162`):

- **Initial sync** (`tip - cursor > 2000` slots): `BlockFetchService.startFetch`
  → Yaci `BlockRangeSync.fetch(from, to)` — one N2N BlockFetch range to the
  tip. Blocks stream into `ShelleyBlockEventPublisher`, which accumulates
  `blocksBatchSize` (100) blocks, partitions them (~10/partition), and
  **forks**: one `CompletableFuture` per partition on virtual threads, blocks
  sequential within a partition, partitions concurrent
  (`ShelleyBlockEventPublisher.processBlocksInParallel:86-120`). Each block
  fans out its Spring events (`BlockEvent`, `TransactionEvent`,
  `CertificateEvent`, …) concurrently on a second executor. Then the
  **merge**: `allOf().join()` → `BatchBlocksProcessedEvent` →
  `PreCommitEvent` → `CommitEvent` → cursor advances to the batch's last
  block.
- **Tip sync** (after `batchDone()`): `BlockSync` chainsync, single-threaded,
  one block = one transaction = one commit.

Two contracts make this correct:

1. **Ordering exists only at batch boundaries.** Within a batch, neither
   blocks (across partitions) nor event types (within a block) are ordered.
   Anything order-sensitive defers to `PreCommitEvent`/`CommitEvent`
   (e.g. `UtxoProcessor.handleCommit`, `AddressTxAmountProcessor`).
2. **`CommitEvent` means "this batch is complete."** A failed block fails the
   batch transactionally; the cursor does not advance; restart resumes from
   the last cursor with a cleanup `RollbackEvent`. There is no mechanism to
   revisit a committed batch.

### What Leios changes

Under Linear Leios (CIP-0164; Musashi prototype), transactions reach the
ledger through **two lanes**:

- **RB-direct**: in the ranking block's own body — flows through BlockFetch
  and `onBlock` exactly as today.
- **Certified-EB**: an RB header *announces* an Endorser Block
  (`leios_announcement = [eb_hash, eb_size]`); the **immediately following**
  RB *certifies* it (`leios_certified = true` + certificate in its body) and
  carries **no transactions of its own**. The EB's transactions become
  ledger-effective **at the certifying RB's chain position**, but their bytes
  are never inside any ranking block — the header's `block_body_hash` covers
  only the RB's own body. They exist only as an EB closure fetched over the
  `leios-fetch` mini-protocol.

The consequence for the store: **BlockFetch alone no longer delivers the
ledger's transaction stream.** A batch of 100 ranking blocks can be
"processed" while the majority of its ledger-effective transactions (EB
closures) were never fetched — and once `CommitEvent` fires and the cursor
advances, nothing ever comes back for them. Every downstream store is
affected: utxo (missing spends/outputs), transaction, assets, account
balances, adapot (EB transaction **fees** are real fees at the certifying
block), analytics.

### Availability of EB data: prototype vs final — the decisive difference

| | Musashi today (`prototype-2026w27`) | Final Linear Leios (CIP-0164) |
| :--- | :--- | :--- |
| EB fetch near tip | ✅ `MsgLeiosBlockRequest` / `MsgLeiosBlockTxsRequest` after notify offers (Yaci's coordinator does this) | ✅ same role, `MsgLeiosBlockTxsRequest` with roaring-style bitmaps |
| EB fetch during catch-up | ❌ **does not exist** — range messages are commented out of the prototype CDDL ("not implemented yet in leios-prototype"); peer retention of old EBs is unspecified; unoffered requests risk stalls/resets (no error response in the protocol) | ✅ **first-class**: "While the node is catching up with the chain after a restart, it will see Praos blocks referencing EBs and use the `MsgLeiosMultiBlockRequest` to get not only the EB but also all transactions referenced therein" (CIP-0164). Certified EB closures are syncable chain data — they must be, or no node could sync from genesis |
| Ledger-effective EBs on chain | certificates are live in the format (w27) but the chain is idle; certified EBs will appear under load | the normal case at scale (up to ~12 MB of referenced txs per EB vs ~88 KB per RB) |

So the problem splits cleanly:

- **Today**: complete initial sync of certified-EB transactions is
  **impossible from the public network**, no matter what Yaci or the store
  do. The design must degrade honestly.
- **Final**: complete initial sync is protocol-supported, and the work is to
  integrate EB resolution into the fork/merge pipeline without breaking its
  two contracts.

## Analysis: where the current pipeline breaks

1. **Completeness (`CommitEvent` contract).** "Batch complete" silently
   becomes false for any batch containing a certifying RB. The cursor
   advances past permanently-missing data. This is the core defect: not a
   crash, but an invisible hole — the same failure class as silent CBOR
   corruption, at pipeline level.
2. **Ordering.** EB transactions are ledger-effective at the certifying RB's
   position. The parallel fan-out has no cross-partition ordering, so EB
   transactions cannot be delivered as free-floating events during a batch —
   they must be **attached to the certifying block** before the fork, so they
   inherit that block's `EventMetadata` (slot, block number, epoch) and ride
   the existing machinery.
3. **Parent lookup across partitions/batches.** The certifying RB's EB hash
   comes from its **parent's** header (`leios_announcement`). The parent is
   usually adjacent (same batch), but at a batch boundary the announcing RB
   is the last block of batch N−1 while the certifying RB opens batch N.
   Batch assembly must carry one small piece of state across batches (the
   previous block's announcement), and derive the in-batch index
   (blockHash → announcedEbHash) **before** forking.
4. **Failure semantics.** Today a failed block fails the batch (fail-closed,
   correct). EB resolution failure must map onto this: in final Leios,
   unresolvable certified-EB closure ⇒ batch failure and retry (it is chain
   data; a peer must serve it). On the prototype, the same rule would wedge
   sync forever — hence an explicit degraded mode, never a silent default.
5. **Tip mode races.** At tip, Yaci's coordinator delivers `onEndorserBlock`
   asynchronously relative to `onBlock`. Announcement precedes certification
   by ≥ `3L_hdr + L_vote + L_diff` (~14 slots at feasible parameters), and
   EBs are fetched at announcement time, so the EB event *usually* lands
   before the certifying block — but the store must tolerate both orders.
6. **Scale.** Batch sizing is block-count-based (100 blocks ≈ 9 MB of RBs).
   With EB closures attached, a worst-case batch approaches
   50 certified EBs × 12 MB ≈ 600 MB. Byte-aware batch sizing, per-batch
   cache growth (`AddressTxAmountProcessor`, utxo caches), and DB write
   amplification all need revisiting — this is the support plan's
   "throughput" phase made concrete.
7. **Semantics leaks.** `EventMetadata.noOfTxs`, block tx counts, fee
   accounting (adapot), and "transactions of a block" APIs must each decide:
   RB-direct only, or ledger-effective? Precedent from CIP-0164: a certifying
   RB has zero own transactions; the EB contributes the payload.

## Decision

Adopt a **two-mode design** with one invariant and three phases.

### The invariant (both modes, both repos)

> Certified-EB transactions are processed **as part of the certifying
> block**, with the certifying block's metadata, inside the certifying
> block's batch — never as free-floating events. `CommitEvent` keeps its
> meaning: when it fires, everything ledger-effective in the batch is either
> processed or **explicitly recorded as a gap**.

### Yaci-side contract (this repo)

1. **Ordering guarantee (tip mode, near-term):** the coordinator already
   fetches EBs at announcement time; formalize the guarantee that a
   certified RB's `onEndorserBlock` event is delivered **before** the
   certifying `onBlock` whenever the EB was resolvable, and add a
   `onEndorserBlockUnavailable(point, reason)`-style signal (or event flag)
   when it was not. Store-side joins then have a deterministic contract
   instead of a race.
2. **Range resolution (final protocol, Phase 2) — batch pull API.** When the
   network supports `MsgLeiosMultiBlockRequest` (or the blueprint range
   messages), Yaci exposes a **synchronous batch-fetch API** — shape:
   `LeiosEndorserBlockFetcher.fetchClosures(List<LeiosPoint>) →
   Map<ebHash, EndorserBlockClosure>` — that internally speaks
   Multi-EB requests (chunking large payloads internally so the caller sees
   one logical call; per-EB failure reported per entry). The store calls it
   **once per processing batch at assembly time, before the fork** (see
   below): one RTT + transfer per 100 blocks, at the pipeline's existing
   synchronization point. This *pull* model is preferred over holding back
   certifying blocks inside the BlockFetch stream (a push "prefetch
   pipeline") — it leaves BlockFetch untouched, puts resolution where the
   whole batch is already in hand, and makes failure handling the batch's
   existing transactional failure. A stream-side prefetch remains a possible
   optimization later; it is not required for correctness.

   **The interface ships now as a placeholder** so yaci-store can code its
   batch-assembly seam against the contract immediately:
   `com.bloxbean.cardano.yaci.helper.LeiosEndorserBlockFetcher` with
   `fetchClosures(List<LeiosPoint>) → Map<String /*ebHash hex*/,
   EndorserBlockClosure>`; an **absent key means unresolved** (strict mode:
   fail the batch; observe mode: record the gap). Until the final protocol
   lands, the only available instance is
   `LeiosEndorserBlockFetcher.unsupported()`, which throws
   `UnsupportedOperationException` carrying the protocol rationale — no
   silent no-op, so a misconfigured strict-mode deployment fails loudly
   rather than committing empty closures. `EndorserBlockClosure` =
   `{point, endorserBlock, transactions, txsComplete}` (helper
   `model/leios`).
3. **Optional merged view (Phase 2/3, config-gated):** a
   `LeiosConfig.mergeCertifiedTransactions` flag that appends EB-sourced
   `Transaction`s (marked with a source flag) to the certifying block's
   `onBlock` transaction list — the zero-API-change path for downstream apps
   that just want "all transactions". Off by default: indexers need
   provenance, and `noOfTxs`/body-hash invariants change. yaci-store does
   **not** use this; it uses the explicit events.

### yaci-store-side design (implementing ADR to follow in that repo)

1. **New `stores/leios` module** (3-file pattern: configuration + processors
   + starter), tables per the support plan: `leios_endorser_block`
   (announced/certified lifecycle, `announced_slot`, `certified_slot`,
   `certified_block_hash`, availability status), `leios_eb_tx`
   (ebHash → txHash/index/size + ledger position), optional `leios_vote`.
   Blocks table gains `announced_eb_hash`, `announced_eb_size`,
   `leios_certified` (small, query-critical columns only).
2. **Batch assembly enrichment** (`ShelleyBlockEventPublisher` seam): before
   the fork —
   (a) build the batch's announcement index (blockHash → announcedEbHash)
   plus the carried-over announcement from the previous batch's last block;
   (b) collect the EB hashes of blocks with `leiosCertified == true`
   (**certified only** — uncertified announcements are not ledger-effective
   and may not be retained by peers);
   (c) in range mode, resolve them with **one call per batch** to the Yaci
   pull API above; in tip mode, take them from the coordinator's
   already-delivered EB events (buffered by ebHash, either arrival order);
   (d) attach each closure to its certifying block.
   Boundary rule: if the batch's **last** block announces an EB, its
   certification is unknown until the next batch's first block — the
   announcement carries forward and its EB (if certified) resolves with the
   next batch.
   During per-block fan-out, a certifying block additionally publishes its
   EB transactions as a `LedgerEffectiveTransactionEvent` (metadata =
   certifying block, source = `CERTIFIED_EB`) — or an enriched
   `TransactionEvent` with per-tx source, whichever the store ADR settles
   on. **Per-block, never one batch-wide event** — block metadata and
   position must ride with the transactions. Existing processors (utxo,
   assets, account, adapot) subscribe where relevant; fee accounting
   includes EB tx fees at the certifying position.
   Ordering note: with attachment done pre-fork, ledger-effective ordering
   under Leios is exactly as strong as today's RB ordering — unordered
   across partitions within the batch, re-established at `CommitEvent`;
   within a certified position, the EB's internal insertion order is
   preserved by Yaci's decoder. EBs have no independent chain position, so
   there is no separate "EB stream order" to maintain.
3. **Two completeness modes:**
   - `strict` (final Leios): unresolved certified-EB closure in a batch ⇒
     batch failure ⇒ transactional rollback + retry (identical to today's
     failed-block behavior). `CommitEvent` regains its full meaning.
   - `observe` (Musashi today, default while on the prototype): certifying
     RBs commit with `leios_endorser_block.status = MISSING_BODY`; a gap
     metric (`certified RBs without EB closure`) is first-class monitoring
     output. Tip-mode EB events fill rows as they arrive (either order,
     reconciled by ebHash). **No pretense of completeness**: any API serving
     ledger-effective transactions surfaces the gap status.
4. **Rollback**: ledger-effective rows keyed by **certifying** slot,
   observational EB rows by **announced** slot; both participate in the
   existing delete-by-slot `RollbackEvent` flow. The cross-boundary case
   (announced before the rollback point, certified after) clears the
   certified linkage but may retain the observation.
5. **Backfill job** (`components/job` pattern): when Phase 2 lands, a job
   scans `MISSING_BODY` rows and resolves them via the range protocol —
   turning prototype-era gaps into completed history without a resync.

### Era prerequisite (immediate, independent)

yaci-store consumes `Era.Dijkstra` transparently in most places
(`EraMapper.intToEra` is dynamic; `era` table keys on the int). Audit the
~54 `Era.Conway` comparisons: `>=` comparisons silently include Dijkstra
(usually correct — e.g. `UtxoProcessor` pointer-skip), equality checks
against "latest era" need review. No `block_kind` column exists today; the
new columns in (1) cover it.

## Phased roadmap

| Phase | Trigger | Yaci | yaci-store |
| :--- | :--- | :--- | :--- |
| **0 — Musashi RB lane** (now) | PR #171 merges | done: Dijkstra parsing, EB observation at tip | bump yaci; era audit; store syncs Musashi RB-direct today with **zero pipeline change** (initial sync works — certified EBs don't exist on the idle chain yet) |
| **1 — Observe mode** (Musashi under load) | certified EBs appear on the testnet | formalize tip-mode ordering guarantee + unavailability signal | `stores/leios` module, blocks columns, batch-assembly enrichment behind `observe` mode, gap metrics, both-order tip reconciliation |
| **2 — Strict mode** (final protocol) | `MsgLeiosMultiBlockRequest` (or blueprint range messages) live on a network | EB prefetch pipeline in `BlockRangeSync`; range-mode ordering guarantee; optional merged view | `strict` completeness mode; `LedgerEffectiveTransactionEvent` consumed by utxo/assets/account/adapot; backfill job for Phase-1 gaps |
| **3 — Scale hardening** (pre-mainnet) | RC-grade throughput targets | fetch concurrency/backpressure tuning | byte-aware batch sizing (cap by cumulative tx bytes, not block count), per-batch cache limits, DB write batching, sustained-load benchmarks across all processors |

Interim option, deliberately **not** on the roadmap: probing prototype peers
with unoffered `MsgLeiosBlockRequest`s for historical EBs, or scraping a
local node's `leios.db`. Both are unpinned, retention-dependent behaviors of
a weekly-respun network — worth a manual experiment, not a design
dependency.

## Alternatives considered

1. **Block the `onBlock` stream on EB resolution today (prototype).**
   Rejected: no historical fetch exists; initial sync would wedge on the
   first certified RB.
2. **Merged view as the only mechanism** (EB txs silently appended to
   `onBlock`'s list). Rejected as the store path: destroys provenance
   (indexers must distinguish lanes), changes `noOfTxs`/body invariants, and
   hides gaps in observe mode. Kept as an opt-in convenience for
   non-indexer consumers.
3. **N2C merged blocks from a local node** (CIP-0164 suggests nodes may serve
   client-facing merged blocks over LocalChainSync). Rejected for initial
   sync: N2C chainsync is the slow path the store already avoids;
   merged-block serving is speculative and node-version-dependent; and
   provenance is lost. Worth revisiting only if the final node ships it and
   a deployment prefers simplicity over sync speed.
4. **Free-floating EB events into the parallel batch** (store listens to
   `onEndorserBlock` and processes independently of blocks). Rejected for
   ledger-effective data: violates the ordering contract (no cross-partition
   order; EB txs would be processed with wrong/no block context) and makes
   `CommitEvent` meaningless. Acceptable only for the *observational* tables
   in Phase 1.

## Consequences

- **Positive:** yaci-store's fork/merge architecture survives Leios intact —
  the enrichment happens at batch assembly (before the fork) and the
  completeness rule at the commit boundary (after the merge), the two places
  that are already synchronization points. No per-processor rework for
  ordering. The observe/strict split gives an honest prototype posture and a
  clean flip when the final protocol lands.
- **Negative / cost:** a new cross-repo contract (EB-before-certifying-block
  ordering) that Yaci must uphold in both tip and range modes; batch
  assembly gains state (cross-batch announcement carry-over, EB buffer);
  observe mode admits — and must visibly report — incomplete history on the
  prototype; Phase 3 scale work is substantial (600 MB worst-case batches
  make block-count batching obsolete).
- **Risk watch:** CIP-0164's open question on serving *old* EBs ("it's not
  already clear what the eviction policy should be") — if final retention is
  bounded, "strict" initial sync from genesis may need EB archives; track in
  `docs/leios/leios-musashi-source-tracking-guide.md`. Vote/certificate
  format drift continues weekly; nothing here depends on vote internals.

## Open questions

1. Should `LedgerEffectiveTransactionEvent` be a new event or a source flag
   on `TransactionEvent`? (Store-ADR decision; new event is less invasive to
   existing listeners, flag is less duplication.)
2. `EventMetadata.noOfTxs` semantics for certifying blocks — RB-direct (0)
   with a separate ledger-effective count, or combined? (Affects existing
   consumers of metadata.)
3. Does adapot treat EB transaction fees identically to RB fees at the
   certifying position? (CIP reading says yes — confirm when reward
   calculation specs for Dijkstra land.)
4. Final-protocol EB retention policy (CIP open item) — determines whether
   strict-mode genesis sync needs archive infrastructure.
5. Musashi interim: do public relays serve unoffered historical EBs at all?
   (One manual probe answers; do not build on it either way.)

## References

- ADR 0007/0010/0011 (this repo) — transport, serialization/listener
  integration, w27 re-pin.
- `docs/leios/leios-usage-guide.md` — the two-lane model and correlation
  rules this ADR builds on.
- `docs/leios/linear-leios-support-plan.md` — store schema sketches and
  phase structure this ADR concretizes.
- CIP-0164 — `MsgLeiosMultiBlockRequest` catch-up design; certifying-RB
  ordering semantics; timing parameters.
- yaci-store pipeline: `StartService`, `BlockFetchService`,
  `ShelleyBlockEventPublisher`, `ExecutorConfiguration`, `CommitEvent`
  (verified at the file/line level, 2026-07-07).
