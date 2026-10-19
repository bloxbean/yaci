# ADR 0013: Leios: complete blocks through the existing API

Date: 2026-10-19

Status: Proposed

Supersedes: the **Decision** part of ADR 0012 (the analysis in ADR 0012 still stands).

Design details and block diagrams: `docs/leios/leios-complete-blocks-design.md`.

## Summary

With Leios, many transactions are no longer inside the ranking block that makes them part of the ledger.
They sit in an **endorser block (EB)** that a later ranking block **certifies**.

Yaci will hide this from applications. When a ranking block certifies an EB, Yaci fetches the EB's
transactions over the same node-to-node connection and hands the application **one complete block** through
the existing `BlockChainDataListener.onBlock(era, block, transactions)` callback. Tip sync and range sync
behave the same way.

Result: Yaci Store, and any other app that uses `BlockSync` or `BlockRangeSync`, keeps working **without code
changes**. Every block it receives carries all of its ledger transactions, and blocks still arrive in chain order.

## Context

### What Leios changes, in plain words

Before Leios, a block's transactions are inside the block. With Linear Leios (CIP-0164):

1. A ranking block (RB) may **announce** an EB: its header carries `[eb_hash, eb_size]`.
   The EB itself is only a list of transaction references (`tx reference hash -> size`). It travels separately.
2. Voters check the EB. If enough stake votes for it, the **next** RB **certifies** it: its header sets
   `certified = true` and its body carries a certificate. A certificate always refers to the announcement in the
   certifying block's **parent** (`prevHash`).
3. The EB's transactions become part of the ledger **at the certifying RB's position, before that RB's own
   transactions** (Haskell node: `applyLeiosClosure` runs before the certifying block is applied). CIP-0164 also
   says that when a certificate is included, no further transactions are allowed, so in practice a certifying
   RB has no transactions of its own.
4. An EB that the next RB does not certify is discarded. Its transactions never reach the ledger from it.
   From `rc-2026w42` on, an EB can also not be certified across an epoch boundary.

So a plain BlockFetch stream no longer contains every ledger transaction. In live Musashi data, a certifying
block is about 1 KB on the wire but brings in thousands of transactions. Example from the KleioScan explorer
(db-sync data, 2026-10-17): block #156614 is 169 bytes on chain and has 5,255 ledger transactions;
transaction `c82dae27…2327` is at index 5254 of that block.

### What downstream apps rely on (Yaci Store as the main example)

Yaci Store receives blocks through `onBlock(era, block, transactions)` and processes them in parallel batches
of 100 blocks. It relies on two things:

1. Each block arrives with **all** of its transactions, in chain order. Inside a batch, blocks are processed in
   parallel; order is re-established when the batch commits.
2. When a batch commits, the cursor moves past it for good. Nothing comes back for missing data.

It reads both `block.getTransactionBodies()` (block processor) and the `transactions` list (transaction, script
and metadata events, `noOfTxs`, `tx_index`). If a certifying block arrives without its EB transactions, the batch
commits with silent gaps: missing UTxO spends and outputs, fees, assets and balances.

### What changed since ADR 0012

- **EBs can be fetched during catch-up on the deployed network.** ADR 0012 was written for the w27 prototype,
  where this was not possible. On the network deployed now (`prototype-2026w40a`), a live probe on 2026-10-19
  (`adr/leios-evidence/leios_fetch_probe.py`) asked the public relay for EBs and their **first 8**
  transactions, with no prior offer:

  | EB | Age | EB served | First 8 txs served | Their hashes match the EB |
  |---|---|---|---|---|
  | slot 3,640,406 | 15 minutes | yes | yes | 8 of 8 |
  | slot 3,495,634, certified by block #156613 | 2 days | yes | yes | 8 of 8 |
  | slot 1,334,920, certified by block #59,997 | 4 weeks | yes | yes | 8 of 8 |
  | slot 3,495,671, never certified | 2 days | no (empty answer) | no | n/a |

  This shows the node keeps certified EBs and serves them on request. It does **not yet** show that a full EB
  (thousands of transactions, megabytes) can be fetched in one go on a busy connection; that test is a
  prerequisite in the plan below.
- **The store should not have to join EB data itself.** ADR 0012 put a new `stores/leios` module and
  batch-assembly enrichment into Yaci Store. That conflicts with the goal of **no downstream change**, and every
  other Yaci user would have to repeat the same join. Doing it once inside Yaci is simpler for everyone.

## Decision

### 1. Yaci delivers complete blocks

For a ranking block that certifies an EB, Yaci delivers to `onBlock` a block whose transaction lists contain:

1. the EB's transactions first, in the EB's order (index 0 .. n-1),
2. then the block's own transactions, if any.

The `Block` object and the `transactions` list are filled consistently: transaction bodies, witnesses,
auxiliary data (keyed by the merged index), invalid-transaction indexes, and full transaction CBOR. Full
transaction CBOR of an EB transaction is built from its parts in the Dijkstra block encoding, exactly like the
block's own transactions.

Ranking blocks that certify nothing are delivered exactly as today.

### 2. Missing EB data is never silently skipped

By default a certifying block is **not delivered until its EB transactions are resolved**.

- Yaci fetches the EB and its transactions over LeiosFetch on a separate connection to the same peer (section 5),
  starting as soon as the certifying block arrives. It does not wait for the peer to offer the transactions:
  in a 10-minute live run the relay sent transactions offers for only 5 of 16 EBs, and offers never exist for
  old EBs during range sync.
- Yaci only asks for **certified** EBs. Nodes discard uncertified EBs, and from w41 on a node disconnects a
  request it cannot answer.
- Every transaction is checked against the EB: blake2b-256 of the transaction bytes as received must equal the
  EB's reference hash (this is not the transaction id).
- If an EB cannot be resolved, Yaci logs it, reports it through a new callback, and keeps retrying with a
  growing delay. Delivery waits; blocks after it wait too. Yaci never delivers the block with transactions
  missing. For Yaci Store this means sync pauses visibly instead of committing a gap; it continues as soon as the
  EB becomes available (for example after reconnecting to another peer).
- An app that prefers to keep going (an explorer, for example) can choose to receive the block without the EB
  transactions (`skipUnavailableEndorserBlocks`, section 3). It then gets the same callback, so it knows exactly
  what is missing.

### 3. Two small, optional settings

**Inline EB transactions** is a global switch in `YaciConfig`, like the existing `returnFullTxCbor`:

```java
YaciConfig.INSTANCE.setInlineEndorserBlockTxs(true);   // default
```

| `inlineEndorserBlockTxs` | What `onBlock` receives for a certifying block | Where EB transactions come from |
|---|---|---|
| `true` (default) | the complete block: EB transactions first, then the block's own | inside the block; no other code needed |
| `false` | the block exactly as today: its own transactions (none in practice) and the Leios certificate. The certified EB is the one announced in the parent block's header | the optional `onEndorserBlock(EndorserBlockEvent)` callback |

The default keeps downstream apps unchanged. An app that wants to handle EBs itself sets it to `false`.

**What to do when an EB cannot be resolved** is a per-connection setting in `LeiosConfig`, used only when
inlining is on:

| `LeiosConfig.skipUnavailableEndorserBlocks` | Meaning |
|---|---|
| `false` (default) | wait and retry; never deliver a certifying block without its EB transactions |
| `true` | deliver the block without them and report the gap through `onEndorserBlockUnavailable` |

The existing `LeiosConfig.mode` still decides whether Leios is used at all (`AUTO` = Musashi magic 164 today).

### 4. Everything new is additive

No existing method, constructor or field changes meaning or signature. The API check
(`adr/leios-evidence/apicompare.py`, every public type compared against `next`) must keep reporting zero
incompatibilities, and JSON output of pre-Leios blocks must not change (new nullable fields are left out of
JSON when null).

New, optional surface:

- `Block.getLeiosEndorserBlock()`: for a certifying block, the EB hash, the EB slot and how many of the block's
  transactions came from the EB (always the first *n*). `null` for every other block, and when inlining is off. Apps that need to know
  where a transaction came from read this; others ignore it. `helper.model.Transaction` gets **no** new field
  (adding one would change its public constructor).
- `BlockChainDataListener.onEndorserBlockUnavailable(...)`: a default (empty) method that reports an EB Yaci
  could not resolve yet, in every mode.
- `YaciConfig.inlineEndorserBlockTxs` (default `true`) and `LeiosConfig.skipUnavailableEndorserBlocks`
  (default `false`), see section 3.
- The existing optional callbacks `onEndorserBlock` and `onLeiosVotes` stay for apps that want raw Leios data.
  With inlining off, `onEndorserBlock` is how an app receives EB transactions. The existing settings
  `fetchTxs`, `maxTxsPerEndorserBlock` and `txsOfferWaitMillis` only affect those raw events.

### 5. Node-to-node first

All of this works over node-to-node, because most downstream apps use it, for tip sync and range sync alike.

EBs are fetched on a **second, small node-to-node connection to the same peer** (LeiosFetch only). The main
connection (ChainSync, BlockFetch) stays exactly as today, including its handshake. A separate connection has
its own network thread, so an EB answer can never wait behind block data the main connection has not read
yet; this is what makes back-pressure safe (see "How it works"). If the peer does not run the Leios
protocols, every EB is reported through `onEndorserBlockUnavailable` with that reason (immediately, without
waiting for a timeout).

Node-to-client is secondary. The node inlines EB transactions into certifying blocks there, but it may send a
certifying block without them when it cannot read the EB. Yaci treats a certifying block with no EB transactions
over node-to-client as an unresolved EB (rule 2).

### 6. Production networks are untouched

On mainnet and preprod there are no Dijkstra blocks and no certificates, so nothing is merged and nothing waits.
Leios mini-protocols are only attached when Leios is active. The Shelley–Conway parsing path stays as on `next`.

## How it works (short version)

A new helper component, the **complete-block assembler**, sits between the network and the application's
listener. It has four small parts:

1. **Announcement memory**: remembers the EB announced by each recent RB, keyed by the RB's hash (4,096 entries,
   enough to cover any rollback). A certifying block looks up its parent by `prevHash`. If the parent was never
   seen (the first block after a start), Yaci fetches it once on a short-lived connection: a header names its
   parent's hash but not its slot, so Yaci asks ChainSync `FindIntersect` for that hash at each of the 1,000
   slots before the child (only the real one exists on chain), then BlockFetches it. If the sync started from a
   known point, that point is used directly.
2. **EB resolver**: on its own connection, fetches the certified EB, checks its hash, fetches all its
   transactions in one request and checks every one. EBs are fetched one after another; the fetch starts as soon
   as the certifying block arrives.
3. **Ordered queue**: every callback is queued in arrival order and delivered on one worker thread. A certifying
   block waits in the queue until its EB transactions are there; blocks behind it wait too. The queue is bounded
   (256 events): when it is full, the main connection's thread waits, so Yaci reads from the node more slowly —
   the same back-pressure a busy listener gives today. This cannot deadlock, because EB answers arrive on the
   other connection.
4. **Merger**: builds the complete block (EB transactions first) and calls the application.

All listener callbacks (`onBlock`, `onRollback`, `batchDone`, `noBlockFound`, …) go through the same ordered
queue on one thread, so an app never sees `batchDone` before the last block of the batch.

What applications may notice, on Leios networks only:

- Callbacks run on a Yaci worker thread (`yaci-leios-complete-blocks`) instead of the network thread. They are
  still called one at a time, in order.
- An exception thrown by `onBlock` is passed to `onParsingError`, as today. Exceptions from other callbacks are
  logged and the next event is delivered.
- `stop()` (and `startSync`, `restart`) first closes the assembler, then the connection: events still queued are
  dropped, as a closed connection drops undelivered data today. Application code running in a callback is never
  interrupted.

Rollbacks need nothing new in the application: EB transactions are part of the certifying block, so they roll
back with it. A certifying block that is still waiting when a rollback behind it arrives stops waiting and is
delivered as on the wire (as today), followed by the rollback.

Diagrams and step-by-step flows: `docs/leios/leios-complete-blocks-design.md`.

## Implementation and live results (step 4)

- Helper: `CompleteBlockAssembler` (wraps the application listener), `EndorserBlockResolver` (separate Leios
  connection), `ParentBlockLookup`, `TransactionListBuilder` (the existing transaction-list code, moved unchanged
  so complete blocks use the same rules). Core: `DijkstraBlockBodySerializer.mergeEndorserBlockTransactions`,
  `LeiosEndorserBlockRef`, `YaciConfig.inlineEndorserBlockTxs`. Wired into `BlockSync` and `BlockRangeSync` only
  when `LeiosConfig.shouldInlineEndorserBlockTxs(magic)` is true, so mainnet and preprod keep the application's
  own listener object.
- Live, unmodified listener (`MusashiCompleteBlocksIT`), checked against db-sync (KleioScan):
  range #156612..#156615 gives 354, **345**, **5,255** (last tx `c82dae27…2327` at index 5254), 356;
  a range starting at a certifying block finds its parent; tip sync over 4 minutes delivered 12 blocks, 5 of them
  certifying, all complete (5,555 / 5,555 / 559 / 4,085 transactions = db-sync).
- Found and fixed on the way: `LeiosFetchAgent` dropped the request a listener sent from inside `onBlock`
  (the outstanding request was cleared after the callback), so the transactions answer was never matched.

## Consequences

Good:

- Downstream apps get the full ledger with no code change. Yaci Store's parallel pipeline is unchanged.
- One implementation inside Yaci instead of one per application.
- A committed batch is complete; an unavailable EB shows up as a visible pause, not as missing data.

Costs and things to watch:

- **Bigger blocks in memory.** A certifying block can carry megabytes of EB transactions. A batch of 100 blocks
  can grow from about 9 MB to hundreds of MB. Apps with block-count batches may later want byte-aware batches.
- **Block fields that describe the wire block stay as they are.** The header's `blockBodySize` and
  `blockBodyHash` still describe the on-chain body. Only the transaction lists grow.
- **Range sync does more round trips**, one EB at a time to start with.
- **Musashi changes often.** Formats are pinned per release (see `adr/leios-todo.md`). The deployed network is
  `prototype-2026w40a`; the w41/w42 respin needs the prepared transport patch.

## Prerequisites before this becomes the default

1. **Done.** A full certified EB fetched in one request: 5,255 transactions (1.3 MB) in about 1 second, every
   hash checked, both with a probe and through Yaci (`MusashiCompleteBlocksIT`).
2. **Done.** The "4 disconnects" in the step 2b run were 2 events, each reported twice: both the chain-sync and
   the block-fetch adapters forward `onDisconnect` (existing Yaci behaviour on every network). The events are
   occasional relay-side closes; Yaci reconnects automatically. A 10-minute run with keep-alives saw one.
3. **Done.** A 10-minute live run: 16 EB offers, 5 transactions offers. Offers are partial, so fetching on
   request (without waiting for an offer) is required.

## Alternatives considered

1. **Yaci Store joins EB data itself (ADR 0012).** Rejected: needs store code changes, a new module and
   cross-batch state, and every other Yaci user would repeat it.
2. **Deliver EB data as separate events.** Rejected for ledger data: events would race with blocks across the
   parallel batch, and a batch could commit before its EB arrived. Kept only as the optional raw callbacks.
3. **Use node-to-client, where the node already merges blocks.** Rejected as the main path: most apps sync over
   node-to-node, and node-to-client needs a local node. Supported as a secondary path.
4. **Wait for the peer's transactions offer before fetching.** Rejected: offers do not exist for old EBs, and the
   relay did not send any in our live run.

## Open questions

1. **How long will nodes keep certified EBs?** Four weeks is shown on Musashi; the final retention policy is an
   open CIP item. If it becomes bounded, syncing from genesis needs another source for old EBs.
2. **Final catch-up protocol.** CIP-0164 plans a multi-EB request for catch-up. The resolver should switch to it
   when it exists; the application-facing behaviour does not change.
3. **Script validity of EB transactions on w40a.** On w40a, EB transactions use the mempool encoding (no
   `is_valid` flag; such transactions are valid). w42 moves to the block encoding with an explicit flag. Yaci maps
   both to the existing `invalid` flag.
4. **Epoch boundary on w40a.** w40a may still allow certification across an epoch boundary (forbidden from w42).
   The EB transactions then count in the certifying block's epoch, which is what Yaci delivers.

## References

- ADR 0010, 0011, 0012 (this repo); `adr/leios-todo.md` (tracker, Musashi baseline, live evidence).
- CIP-0164; cardano-blueprint `leios-fetch` / `leios-notify` messages.
- Haskell `ouroboros-consensus` `Shelley/Ledger/Leios.hs` (`applyLeiosClosure`) at `cbd10033` (the w40a pin).
- KleioScan Musashi explorer API (`https://kleioscan.com/api/musashi`) for block and EB data.
