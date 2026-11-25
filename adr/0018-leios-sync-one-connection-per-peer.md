# ADR 0018: Leios sync on one connection per peer: pipelined LeiosFetch, request-level flow control, in-order delivery

- Status: **Proposed** (design and plan, backed by a measured proof of concept). Implementation: phases 1–2 are on the
  branch `feat/leios-one-connection`, opt-in (`LeiosConfig.oneConnection`, `-Dyaci.leios.oneConnection`); not yet
  on `next`.
- Date: 2026-11-25
- Tracking issue: #260. Follow-up: #261 (measure the header overhead of BlockFetch windows, compare with the Haskell
  node, tune the defaults).
- Relates to: #255 (Leios sync speed), #253 (fair mux egress), PR #256 (ADR 0017: D1 timings, D2 side-connection
  keep-alive, D4 read-ahead prototype with a connection pool)
- Supersedes, once implemented and default: ADR 0016 D3b (synchronous delivery) except within D4c, and the ADR 0017 D4
  pool. ADR 0016 D4a and D4b's connections, and ADR 0017 D2, stay, used only by D4c (D8).

## Context

To deliver a certifying block (a Dijkstra block with `leiosCertInBody == true`), Yaci must first fetch the endorser
block (EB) announced by the block's parent, and then the EB's transactions. Today, `CompleteBlockAssembler.onBlock`
does this inside the listener call, on the main connection's Netty event loop, over a separate side connection
(ADR 0016 D3b, D4a). That costs two round trips per certifying block, one block at a time:

- **Full Musashi run:** 129,229 blocks, 44,683 certifying, 61.8 M EB transactions, 9 h 21 min (1.33 EBs/s).
- **Where the time goes:** about 89% of wall time is spent waiting on EB fetches (ADR 0017 Stage 0).

### Constraints

1. **Downstream contract unchanged.** `BlockChainDataListener` receives the same callbacks, with the same blocks and
   transactions, in the same order and positions, serialized as today:
   - `onBlock` with complete blocks;
   - one `batchStarted` and one `batchDone` per `fetch(from, to)`;
   - in `BlockSync`: `rollforward*`, `intersactFound`/`intersactNotFound`, `onRollback`;
   - `onParsingError`, `onEndorserBlockUnavailable`, `onDisconnect`;
   - halt and skip.

   Every block is delivered exactly once, with no gap. yaci-store, the main downstream, needs no code change. The
   Downstream contract section lists what it relies on, item by item.
2. **One connection per peer.** ChainSync, BlockFetch, KeepAlive and LeiosFetch share the main N2N connection.
   - The owner accepted one exception on 2026-11-25: D4c, today's path, used for a range that is not a verified
     path on the peer's selected chain, and after a fault.
3. **Certified EBs only.** A node disconnects a request for an EB it does not hold (ADR 0013a).
4. **Bounded memory, with backpressure.** The main deployment is yaci-store syncing from a local or LAN node, where
   Yaci's CPU and memory, not the network, are the limit.
5. **Few round trips.** Public relays are shared with other Yaci users.
6. **Nothing changes unless enabled.**
   - With the feature off (D7), every network behaves as today.
   - With it on, listener callbacks are unchanged in every era, but the request pattern changes (D4). It becomes the
     default only after a mainnet throughput check.

### Why Yaci needs a side connection today

The cause is Yaci's original design: listener callbacks run on the connection's event loop.
- `channelRead` → `BlockfetchAgent` → listener → `CompleteBlockAssembler.onBlock` all run on that loop.
- `onBlock` blocks it while the EB is fetched. A LeiosFetch reply on the same socket could only be read by that
  blocked thread, so the sync would deadlock.
- `BlockRangeSync` asks BlockFetch for the whole range in one `RequestRange`. The only brake is TCP: blocking the loop
  or switching `autoRead` off. On a shared socket both also stop the LeiosFetch replies the front block waits for.
- `LeiosFetchAgent` allows one request in flight, so one connection carries one EB at a time.

Fair egress (#253) does not remove the side connection. It changes how Yaci *sends*, and during sync Yaci sends only
small requests. #253 stays a separate track; it is needed by Yano when serving large replies to followers.

### The reference: the Haskell node (ouroboros-consensus `840e789b`, network `96ee6e0e`, the Musashi w42 pin)

- **Connection.** LeiosNotify (18) and LeiosFetch (19) run on the same node-to-node mux as ChainSync and BlockFetch.
  They start on demand. No handshake version gates them, so a peer that does not run them must never be sent one.
- **Client.** `leiosFetchClientPeerPipelined` keeps up to 150 requests in flight (`leiosFetchPipelineDepth`), bounded
  by outstanding reply bytes (about 5 MB for an ordinary peer). Replies match requests in FIFO order; there are no
  request ids.
- **Server.** It answers in order. An unknown EB raises `ExnLeiosInvalidRequest`, which drops the connection.
  - `MsgLeiosBlock` is at most 660 kB.
  - `MsgLeiosBlockTxs` is at most 550 kB, and a transactions request asks for at most 500 kB.
- **Catch-up.**
  1. A ChainSync roll-forward of a certifying header records the peer as offering the EB
     (`checkMsgRollForwardForLeiosOffers`).
  2. BlockFetch keeps fetching ahead.
  3. Chain selection verifies the certificate, then *parks* the certifying block and its successors until the EB's
     transactions are stored, and adopts them in order.

  LeiosNotify does not offer historical EBs.
- **Flow control** is by request: what is requested and not yet consumed stays under the peer's ingress limit. The
  mux never pauses a socket.

This ADR follows that model, with Yaci's delivery queue in the role of chain selection's parking.

## Decisions

### D1. Threads: one pinned event loop per start; nothing on it waits for the network

New invariant **I-NoWait**: no Yaci code running on a start's event loop blocks on I/O of any connection. Listener
code may block, as today; it then holds only its own start. No producer waits for credit or for delivery. The one
exception is D4c, today's synchronous path, which waits on its side connection's own I/O loop (D4).

**Scope (r11).** Until D10 (phase 4), D1 and D2 apply to *windowed starts*: `BlockRangeSync` with the feature on
(D7). `BlockSync` keeps today's threads, delivery and resume rules, with the feature on or off.

- **One event loop per start.** A *start* is one `fetch`/`startSync` session of a helper. Windowed starts pin it
  now; `BlockSync` does with D10.
  - Each start owns a new single-thread event loop: never the caller's, and never one shared with another start. It is
    shut down after the start retires (review r3: Fable F16).
  - Every connection of the start registers on it: the first one, reconnects and internal replacements (D3).
  - All of the start's timers and lifecycle transitions run on it too.
- **A windowed start's listener callbacks run serialized, on a delivery thread** (I1, C1; implementation, r10;
  scoped in r11).
  - This applies to windowed starts only. `BlockSync` keeps today's delivery on its connection's loop until D10.
  - Protocol threads only enqueue (D2). One drain at a time per start delivers from the front of its queue on a
    pooled delivery thread, never on a protocol loop. So keep-alive, handshakes, timers, closes and retirement always
    proceed, also while a listener is slow.
  - The one place this does not hold is D4c, which is today's path: after the queue has drained it delivers
    synchronously on the start's loop, so a slow listener holds that loop as today, with the watchdog off (D4).
  - The pool is shared and bounded (64 threads). Scheduling a drain never blocks the submitter; a drain beyond 64
    waits in the pool's queue.
    - A drain run delivers one quantum (64 entries or 50 ms), then resubmits itself to the back of the pool's queue,
      so a busy start does not keep a worker (r12).
    - 64 callbacks blocked at once on distinct listeners occupy every pool thread. Other starts' drains then wait in
      the pool's queue until one returns.
  - Today callbacks run on the current connection's loop, which may differ after a reconnect. yaci-store makes no
    thread-identity assumption; it needs serialization and happens-before (C1), which the single drain gives.
  - **One exception is kept from today, with its count and thread.** `Session.start` calls `disconnected()` on each
    agent, synchronously on the caller's thread, before the channel is attached.
    - For the *initial* start they are delivered at once on the caller's thread, not queued.
    - On a reconnect, which runs off the loop, the same notifications enter the start's queue as entries, in order
      and with today's count, and its drain delivers them. They never run beside a callback.
- **Across windowed starts that share a listener** (review r3/r4: Codex F3; r11: phase 2 review round 5):
  - **Nested start-up, as today.** The new start's `Session.start` notifications run synchronously on the caller's
    thread, re-entrantly inside the old callback, as today. For yaci-store that is `onDisconnect`, inside
    `batchDone`. They are not gated, so nothing waits.
  - **The gate.** A windowed start's drain does not enter the listener while a callback of another windowed start on
    the same listener (by identity) is running. Its entries stay queued. When that callback returns, the turn is kept
    for the first waiting start (first come, first served), and that start is rescheduled. No thread ever waits on
    the gate.
    - Registering as a waiter and giving up the drain are one atomic step, so no wake-up is lost.
    - A drain with a waiter behind it hands over after one entry, so neither start starves the other.
    - A start whose kept turn finds nothing to deliver, because its backlog was pruned, passes the turn to the next
      waiter (r12). It does so only after rechecking, under the gate's lock, that no backlog arrived meanwhile; if
      one did, the turn stays with it (r13).
    - Not gated: D4c's direct delivery, the start-up notifications, and a `BlockSync` start until D10.
  - **Exceptions.**
    - An exception from a *block* callback becomes that block's `onParsingError`, which is part of the running
      callback, as today.
    - An exception from a *marker* callback, such as `batchDone` throwing after `startSync`, is never translated into
      `onParsingError`. It propagates to the start's channel error handling, as today's `Agent.receiveResponse`
      lets it (review r4: Codex F3).
    - An `Error` is logged. In every case the drain continues with the next entry (D2).
  - Today two starts' loop callbacks can overlap. Between windowed starts that overlap is removed; nothing is
    reordered (deviation V5). The `BlockRangeSync` → `BlockSync` handoff, for example the new `BlockSync`'s
    intersection callbacks during the old range's `batchDone`, overlaps as today until D10.
- **A slow or blocked listener holds its start's drain** (C6).
  - Windows reserve credit (D5) before they are requested, so new windows stop at the budget and resume as the queue
    drains.
  - Nothing waits for credit: no protocol thread and no producer.
  - D4c starts only after the queue has drained, and then delivers synchronously, as today (D4).
  - `BlockSync` keeps today's delivery until D10.
  - It can no longer deadlock the sync, because no protocol loop waits for a listener or for a reply.
- **Reconnect waits** (connect, retry sleep, handshake polling) never run on the start's loop. Deadlines and the stall
  watchdog keep running during an outage.
- **EB checking and mapping** (Blake2b, `withEndorserBlockTxs`, `TransactionListBuilder`) run on a small CPU pool and
  post the result back to the loop.
- **Stop.** `stop()` retires the start at once:
  - `isRunning()` is false when it returns;
  - no callback of the start is admitted after it returns, and the retired start's backlog is pruned at once;
  - a `fetch()` on it writes nothing, as after today's physical stop (review r4: Codex F16);
  - it closes the channel asynchronously, and never waits for the loop or for delivery (I-Stop).

  A callback already running may finish, as today. Two callbacks still pass, as today: an admitted block's
  `onParsingError`, and the close's `onDisconnect` (I-Stop).
- **Halt.** A halt closes delivery at the position of its `SYNC_HALTED` report instead: every entry queued up to and
  including that report is delivered, and later entries are discarded (r11).
- **Closure is one state per queue** (r12): open, halted or closed, changed under the queue's lock. It decides both
  what enters the queue and what is delivered.
  - An explicit `stop()` or a replacement closes the queue, and wins over a halt: it clears the halt's position, and
    a halt reported later does not reinstate it.
  - Once halted or closed, no ordinary entry enters. A producer that passed its own retirement check before the
    closure is rejected at enqueue.
  - The two exceptions above still pass.
- **Start keeps today's behaviour on the caller's thread.** yaci-store calls `BlockSync.startSync()` from inside
  `batchDone`, and it waits there for the connect and handshake. For a windowed range that thread is the old start's
  delivery thread, or its loop during D4c.
  - That is listener code blocking its own, retiring start, which I-NoWait allows.
  - The new start's connection is on its own loop, never the waiting thread, so the wait cannot block it (review r3:
    Codex F3).

### D2. In-order delivery queue (ADR 0017 D4 semantics, drained on a delivery thread, never a protocol thread)

Scope: windowed starts (D1). `BlockSync`'s callbacks go through the queue with D10 (phase 4).

- **Queue.** Entries enter in receive order. An ordinary block is *ready*; a certifying block *waits* for its EB.
  `batchDone`, rollback, intersection, disconnect and every other public callback of the start are queued
  **markers**, so their position relative to blocks is the receive order, as today.
- **One drain** delivers from the front only, on a delivery thread (D1), never on a protocol thread. It runs when an
  entry arrives, when a fetch completes, and on timers.
- **Fast path.** With nothing waiting, the drain delivers an entry at once, as today. On a network without Leios,
  and in Byron, no entry waits.
- **Phase 2 form** (r11). A certifying block is still completed before it is queued (`CompleteBlockAssembler`, as
  today), so entries wait only for the gate (D1). The commit rule and the front deadline below are not in the queue
  yet; they come with phase 3, with LeiosFetch on the main connection.
- **Rules from ADR 0017 D4, kept as reviewed:**
  - announcements are indexed on read;
  - the 45 s budget starts at the front;
  - `RETRYING`, skip and halt are emitted only at the front;
  - `fetch()` is admitted after the queue drains, or from inside `batchDone`.
- **Terminal entries** (r11). `batchDone` and `noBlockFound` entries carry the range they end. A `fetch()` from
  inside `batchDone` is admitted only when that callback ends the current range, checked by that identity.
- **Halt and stop** (r11). A halt closes delivery at the position of its `SYNC_HALTED` report: every entry queued up
  to and including it is delivered, then later entries are discarded. `stop()` prunes the backlog at once (D1).
  - One closure state per queue (open, halted, closed), under its lock, decides what enters and what is delivered
    (r12). A `stop()` or replacement wins over a halt, also over one reported later. After closure no ordinary entry
    enters, even from a producer that passed its own retirement check (D1).
- **Listener throwables** (r11). Whatever a callback throws, the drain continues with the next entry.
  - A block callback's `Exception` becomes that block's `onParsingError`, in its place.
    - It carries the original block bytes, which are retained through deferred delivery and charged to D5.
    - So the payload does not depend on `returnBlockCbor` or on whether the block was queued.
  - A marker callback's failure goes to the start's channel error handling, never to `onParsingError` (D1).
  - An `Error` is logged.
- **Commit rule** (phase 3). Success, retry, skip and halt are decided by one atomic commit against the block's
  deadline and the start's cancellation, as the synchronous path does today.
  - A result that completes after the deadline loses to the timer.
  - A stop that clears the queue wins over a drain that already looked at the front.
- **The front block's deadline and outcome belong to the logical block, not the connection** (phase 3; review r3:
  Fable F14, Codex F11).
  - They are keyed by block hash for the start, with the raw block bytes needed for a skip-mode delivery.
  - The deadline timer runs on the start's loop, also while disconnected.
  - A fetch lost to a connection loss counts as a failed attempt and emits `RETRYING` under today's rules.
  - At expiry, skip or halt is committed at once, connected or not, using the retained bytes.
  - So a peer that drops the connection on every request for a pruned EB still ends in today's skip or halt within
    the 45 s budget.
  - **The retained front stays at the head of the queue** (review r4: Fable F22). The `onDisconnect` markers of a
    genuine loss queue behind its outcome, as today; when the front is dropped as orphaned, they come first (table
    below).
  - **After a reconnect with a retained front** (review r5: reviewer3 R3-F1, R3-F5; r6: R3-F8).
    - **`BlockRangeSync`:** the new connection first finishes the front's EB over LeiosFetch; its block bytes are
      already held. Only then does step 1 run, from the resume position as it stands. A range's front is on the
      verified path and has no sibling, so this never stalls on an orphan.
    - **`BlockSync`:** the front `B` (parent `A`) may have been rolled back during the outage. So the reconnect first
      sends an internal `FindIntersect([B, A])` (I-Suppress), and the reply is held:

      | Reply | Then | Genuine loss shows | Private replacement shows |
      |---|---|---|---|
      | found at `B` | resolve `B`'s EB (front isolation; `[A, A]` first if `A`'s announcement is missing) | `onBlock(B)`, `onDisconnect`, `intersactFound(B)`, `onRollback(B)`, successors: today's trace | `onBlock(B)`, successors |
      | found at `B`, `B` halts | stop | `SYNC_HALTED`, as today | `SYNC_HALTED` |
      | found at `A` | `B` was orphaned; drop it atomically, with no retry loop and no false halt | `onDisconnect`, `intersactFound(A)`, `onRollback(A)`, the sibling | `onRollback(A)`, the sibling |
      | not found | drop `B` atomically | `onDisconnect`, `intersactNotFound` | `intersactNotFound` |

    - **Skip expiry while that `FindIntersect` is in flight** delivers `onBlock(B)` at once. If the reply is then
      found at `A`, the rollback to `A` is delivered, after `onDisconnect` and `intersactFound(A)` for a genuine loss,
      before the sibling. So the listener never sees two competing children without a rollback between them.
    - A front is never fetched again by ChainSync from its parent, so it is never re-received.
  - **A rollback or a different successor supersedes it atomically** (review r4: Codex F19). The retained block,
    its timer, its outcome and its budget are dropped in one step, before the rollback is delivered. A deadline that
    fires after that is ignored. So an abandoned-fork block is never delivered after its rollback, and a stale timer
    never halts the new fork.
- **Connection loss and internal replacement** (r10: the queue survives, as review r1's option (b), Fable F1).
  1. Entries already queued stay queued and are delivered, in order. They are verified blocks of the requested path
     (D4), so the connection they came from does not matter.
  2. On a genuine connection loss, the `onDisconnect` markers queue behind them. An internal replacement is private
     (D3).
  3. A range resumes from its last *queued* block. The repeated block is dropped by its exact hash, so there is no
     duplicate and no gap.
  4. `BlockSync` keeps today's rules until D10, then D10's.
- **Resume position** (review r3: Codex F4). For a range, it is the last *queued* block (above). `BlockSync` keeps
  today's resume rules until D10. With D10 it is the last of these that was delivered, never one only received:
  - a block whose delivery concluded with success or skip;
  - a rollback point;
  - an intersection point.

  - A block whose delivery ended in `onParsingError` does not move it, as today.
  - A rollback to origin does not move it either, as today: `ChainsyncAgent` keeps its point when the rollback hash
    is null. Changing that is outside this ADR (review r4: Codex F4).
- **Exactly once.**
  - Each entry leaves the queue once.
  - A resumed range repeats its resume block (the range start is inclusive). That block is dropped by its exact hash,
    once.
  - The duplicate check is armed only by a resume of *the same range* after at least one queued block, never across
    separate `fetch` calls.
  - **Deliberate change.** Today a reconnect inside a range delivers that block and `batchStarted` a second time
    (`BlockfetchAgent`). Under D2 neither is repeated, per the owner's exactly-once requirement. A block whose
    listener threw is not delivered again after a reconnect either; it got its `onParsingError`. It is listed as
    deviation V1 in the Downstream contract.

### D3. LeiosFetch on the main connection, pipelined like the Haskell client

- **Agent.** The main connection registers a `PipelinedLeiosFetchAgent`.
  - It sends nothing until a certifying block needs it, so peers that do not run LeiosFetch never see protocol 19.
  - It is used only after the *current* connection's handshake has been accepted with V15 or later, not app-layer
    (ADR 0016 D4a's rule), and not before that handshake completes.
- **Requests.**
  - A FIFO of requests in flight, each with its own future; replies match in order.
  - Caps: count (default **150**) and expected reply bytes (default **5 MB**), Haskell's limits, both configurable in
    `LeiosConfig`.
    - Measured: at these limits, one connection fetched 200 EBs from the public relay at 29.2 EBs/s (8.5 MB/s, 141 in
      flight), against 1.6 EBs/s one at a time.
    - Removing the byte cap was no faster, and slower against a local node (Evidence).
  - One request is always allowed, so an oversized reply still makes progress.
  - The maximum depth reached is logged at the end of each range and exposed for tests, so a sync that cannot keep the
    pipeline full is visible.
- **Per EB.**
  1. The EB request.
  2. When the EB arrives, *all* its transaction batches are requested at once.
- **Generations.** The FIFO, writes, readiness and abort belong to one connection generation.
  - Abort marks the generation terminal *before* failing its futures, and only that generation's connection writes.
  - A request written cannot be cancelled. Its reply is drained and discarded, or the connection is replaced. Its
    budget credit is held until then (D5).
- **Stalls: one clock per mini-protocol** (review r3: Codex F12, Fable F15).
  - A protocol's clock starts when it writes a request that expects a finite reply: a BlockFetch request, a LeiosFetch
    request, or a range's ChainSync header request.
  - The clock is reset only by bytes received *for that protocol*. So keep-alive replies cannot hide a stalled window
    or EB.
  - In `BlockSync`, a `FindIntersect` and each `RequestNext` also arm the ChainSync clock until their first response
    (review r4: Codex F12).
    - A request answered by `MsgAwaitReply` is parked, not stalled, and disarms the clock. So an idle `BlockSync` at
      the tip is never replaced, with or without keep-alive traffic.
    - A peer that never sends the first response is replaced.
  - Time spent inside a listener callback is excluded: a clock reads
    `now − max(last byte for the protocol, end of the last callback)`. A listener that blocks the loop is never taken
    for a stalled peer.
  - At 15 s, the connection is replaced under the D2 rule.
- **Classifying a close** (review r4: Codex F24, Fable F28). Each close is classified once, against the channel
  that closed and the LeiosFetch requests written on it and not yet answered, before abort clears them. The later
  choice to continue windowed or enter D4c is separate.
  - **Private:**
    - the watchdog's own replacement;
    - by owner decision, a remote close of the *shared* channel while a LeiosFetch request is outstanding on it. A
      Haskell peer closes the connection on a request for an EB it has pruned, and today that only loses the side
      connection (review r4: Codex F20).
  - **Genuine:** any other close, including a close of the main channel during D4c, which carries no LeiosFetch.
    - A close of D4c's side connection stays inside the resolver, as today.
  - **A private close becomes genuine** if the reconnect and handshake do not succeed within 15 s. Its `onDisconnect`
    is then delivered under the D2 rule, and `isRunning()` behaves as during today's reconnect. A long outage is
    never hidden (review r4: Fable F28).
    - Once promoted, the close is genuine for good. When the reconnect later succeeds, its re-intersection callbacks
      are delivered as for any genuine reconnect (review r5: reviewer3 R3-F7).
- **Front isolation after a LeiosFetch close** (review r5: reviewer3 R3-F3).
  - When the shared channel closes with LeiosFetch requests outstanding, or the front block is on its second attempt
    or later, only the front block's EB is fetched, one request at a time. Prefetch and every other LeiosFetch request
    wait until the front completes.
  - So an EB that the peer pruned, further back in the pipeline, cannot close the connection again and again under a
    healthy front, until the front is skipped or halted.
- **What a private replacement shows the listener:**
  - no `onDisconnect`;
  - in `BlockSync`, nothing from the re-intersection at the resume position: neither its `intersactFound` nor its
    `onRollback` *to that same point*;
  - a rollback to an *earlier* point, meaning the peer's chain changed, is delivered as a genuine rollback;
  - an `IntersectNotFound`, meaning the resume point itself is gone, is delivered as `intersactNotFound`.
    yaci-store then publishes a request to restart, which its processor may act on or ignore under its own settings;
  - `isRunning()` stays true, and the keep-alive getters keep their values.

  So a private replacement changes no downstream state; yaci-store would otherwise write a `rollback` row for a
  same-point rollback. A genuine loss behaves as today.

### D4. `BlockRangeSync` flow control by request: windows over a verified path, today's path otherwise

This replaces blocking the event loop and `autoRead` off, both of which deadlock on a shared socket. `fetch(from, to)`
keeps its signature and its meaning: the blocks of the chain from `from` to `to`, as the peer serves them by hash.

**Principle (owner decision, 2026-11-25).** Yaci windows a range only along a path it has *verified* to lead from
`from` to `to`, before it publishes any block whose place on that path is not final. Every other case, and the rest of
any range after a fault, uses today's path, D4c. Every outcome is therefore one that today's path produces under the
same peer behaviour (review r4: Codex F1, F7, F17, F18; Fable F21, F23).

- **Step 1: classify** (internal, I-Suppress). ChainSync sends `FindIntersect([to])` and `FindIntersect([from])`.
  - **Windowed path:** both are found on the peer's selected chain, and either `from.slot < to.slot`, or `from` and
    `to` are the same point.
  - **Today's path (D4c) for the whole range:** anything else. That covers `to` or `from` on a retained fork or
    unknown, reversed bounds, and equal slots with different hashes (a Byron epoch-boundary block shares its
    successor's slot).
    - Today's single request then gives today's answer: a retained fork served by hash, or `NoBlocks` with no other
      callback.
- **Step 2: anchor on both endpoints** (review r4: Codex F18, F23; r5: reviewer3 R3-F4). Internal BlockFetch
  `[to, to]` and `[from, from]` (one fetch when `from == to`) fetch both blocks and check their hashes, under
  I-Suppress.
  - `to`'s header gives `to.blockNo`.
  - `from`'s header gives `from.blockNo`, `leiosCertInBody` and `prevHash`, so the remaining header count is known
    exactly, and D4b can run before anything is published.
  - `NoBlocks` for either means the peer dropped it since step 1: the whole range goes to D4c.
  - A parse error in either also sends the whole range to D4c, so today's `batchStarted` and `onParsingError`
    happen unchanged (review r6: reviewer3).
  - `from`'s block is kept as the range's first block. `to`'s is dropped and fetched again in its window.
- **Step 3: publish `from`.** If `from` is certifying, its parent's announcement is looked up first (D4b). If `from`
  is in the mutable zone (`from.blockNo > F`), it waits for that zone's verification like any block there.
- **On a windowed resume** (review r5: reviewer3 R3-F5). After a connection fault, the range resumes at the resume
  position `R`, which was already queued.
  - Step 2 fetches only `[to, to]`, then ChainSync streams the successors of `R`. `R` is not fetched or
    resolved again.
  - If `R == to`, the range is complete, and `batchDone` follows.
- **Windows, published in two zones.** Let `F = to.blockNo − k`.
  - k is the security parameter (2,160 on mainnet, 108 on Musashi), a `LeiosConfig` setting with default 2,160.
    Over-estimating it is safe.
  - **The final zone: blocks at or below `F`.** These are published as their windows arrive.
    - `to` was on the peer's selected chain, so that chain's tip was at or above `to`'s height.
    - A node never rolls back more than k blocks, so any chain it switches to later keeps every block at or below
      `F`.
    - So a block at or below `F` on the path read is an ancestor of `to`. This depends only on `to`'s own block
      number, not on a tip read at another moment.
  - **Block numbers.** For Byron, the block number is the `difficulty`. An epoch-boundary block shares its
    predecessor's, so the mutable zone can hold k blocks plus any epoch-boundary blocks in it (review r5: reviewer3
    R3-F6).
  - **The mutable zone: blocks above `F`.** It holds k blocks before `to` (plus any epoch-boundary blocks), or fewer
    when `from` is above `F`.
    - Its headers are read and must end exactly at `to`'s hash before any block of this zone is published.
    - The header buffer always holds k + 2 headers for this, by count and by bytes, reserved in D5 (review r4: Codex
      F22). That covers one epoch-boundary block, the most a mainnet window of k blocks can hold. If a zone needs
      more, the rest of the range goes to D4c.
    - Its bodies are then fetched by hash along that verified path, as today's iterator walks it.
  - **Header requests in flight** never exceed the blocks left before `to`, known from `to`'s block number. So no
    request passes `to`.
    - Termination is by point.
    - At equal block number (a Byron epoch-boundary block after a regular block), one more header is requested while
      `to` has not been reached.
  - **Before `to`.** If the header stream parks (`MsgAwaitReply`), or passes `to.slot` without `to`, the peer has
    left `to`'s chain. The rest of the range goes to D4c.
  - **Window size.** BlockFetch asks for windows of at most B bytes (default 2 MB) and W blocks (default 2,000).
    - A Byron header gives no body size. A Byron block therefore counts as the average size of the Byron blocks the
      range has already received; D5 charges its real size on receipt.
    - Byron has no certifying blocks, so its queue never waits.
  - **Admission.** A window reserves its bytes (D5) before it is requested, and is requested only if the
    reservation is granted. A window larger than the budget is granted only on an empty queue with nothing reserved
    (D5). A refused window waits for credit; no thread does.
  - **Every window is verified (I-Range):** its blocks must be exactly the expected points, in order.
- **Faults** (review r4: Fable F26). The range always freezes first, at the last contiguous, verified, queued block.
  Window, body and EB work beyond that block is retired by generation.
  - **A connection fault** (a genuine or private close, or a stall): after the reconnect, step 1 runs again from the
    resume position, and the range continues windowed if it still classifies, as today's
    `RequestRange(last received, to)` would serve that path.
  - **A verification fault:**
    - a mismatch, an early `BatchDone`, or `NoBlocks` for a window;
    - a ChainSync `RollBackward` other than the first reply after an intersection to *exactly* the solicited point;
    - leaving `to`'s chain before `to`.

    The rest of the range goes to D4c, from the last queued block.
  - The repeated first block of any continuation request is dropped by exact hash, as within any one range. The
    same seam rule applies wherever a request starts at a block already queued.
- **D4c. Today's path** (review r4: Codex F21; Fable F27).
  - **What it is.** One `RequestRange` by hash. Delivery is synchronous, as today, with today's TCP backpressure.
  - **Connections and threads.**
    - Entries queued before the seam are delivered by the queue's drain (D2). After it, D4c's public callbacks are
      delivered directly: synchronously, on the start's pinned loop, not queued and not gated (D1).
    - EBs come over today's side connection, and parent lookups over today's `ParentBlockLookup`. Each has its own
      I/O loop, never the start's.
    - Blocking the start's loop on that side I/O is the one exception to I-NoWait, because it is today's synchronous
      path.
  - **Entry** (review r5: reviewer3 R3-F2).
    - D4c always starts on a fresh main connection: a private replacement. So ChainSync, BlockFetch and LeiosFetch all
      start idle. No pipelined `RequestNext`, parked `MsgAwaitReply`, BlockFetch window or LeiosFetch request carries
      over, and nothing needs draining.
    - **The order is fixed** (review r6: reviewer3 R3-F9):
      1. retire the old main connection;
      2. complete the retained front, if any, synchronously over the side connection. No main connection exists
         then, so nothing waits on the start's loop, and no promotion clock runs: this replacement was started by
         Yaci, not by a close;
      3. open the fresh main connection and complete its handshake;
      4. wait until the delivery queue has drained (r11);
      5. switch delivery to direct: synchronous, on the start's loop;
      6. send D4c's request.

      If the front halts in step 2, steps 3–6 are skipped. If the front was `to`, `batchDone` completes the range
      instead.
    - **Steps 3–6 under a loss** (r12). If the fresh connection is lost before the request is sent, the wait for the
      drain is invalidated, and the range stays at D4c entry. The next accepted connection repeats steps 3–6.
    - Steps 5 and 6 are one transition on the start's loop. It is taken only after checking the range, its
      generation, that its connection is still the current accepted one, and that the queue has drained.
    - The direct-mode grant also requires the queue to be open, not only drained (r13). A start stopped after the
      range's checks is refused the grant and sends nothing.
    - **Delivery mode per range** (r12). The one transition that first starts a range, whether reported by the
      handshake or by the admitting `fetch()`, sets queued delivery for it. So a direct D4c never carries over.
    - The next range's step 1 therefore also starts on idle protocols.
    - The main connection carries no LeiosFetch while D4c runs.
    - A seam duplicate, the repeated first block of the D4c request, is dropped before `CompleteBlockAssembler` and
      EB resolution. Its announcement stays in `EbAnnouncementCache` for its successor.
    - From phase 3, the queue counts as drained when it is empty, or holds only the retained front. Phase 2 has no
      retained front, so it waits for an empty queue.
    - That front is handed to D4c as its first block: it completes over the side connection, with its deadline and
      attempt count carried over, not restarted.
  - **The stall watchdog is off for the main connection** during D4c, as on today's path. Its synchronous completion
    counts as callback time.
  - **Resources.** The start closes D4c's side connection and lookup on completion, `NoBlocks`, halt and stop.
    `stop()` during the side handshake closes it without waiting (I-Stop).
  - **Outcomes.** Its outcome is today's. For a `NoBlocks` on a continuation, today's agent reports its advanced
    cursor as `noBlockFound(last received, to)`, and D4c keeps that.
  - **When it runs.** In practice D4c runs when an endpoint is off the peer's selected chain, for example a tip
    orphaned between yaci-store reading it and calling `fetch`, or after a verification fault. It is the one place
    where a range uses a second connection, and it runs at today's speed.
    - It is logged once per range and counted in a metric, so a sync that falls back often is visible.
- **Callbacks.**
  - **`batchStarted`** comes immediately before the range's first published block (review r5: reviewer3 R3-F4).
    - On the windowed path, `from` is fetched internally, so `batchStarted` never precedes a `noBlockFound`.
    - On D4c for the whole range, it is tied to the wire `StartBatch`, as today.
    - It is exposed once per range; a later operation of the same range never exposes it again (V1).
    - A disconnect after an internal `StartBatch` and before any published block therefore shows no `batchStarted`
      before `onDisconnect`. Today it shows one, and repeats it after the reconnect (part of V1).
  - **`batchDone`** comes once, after the last block of the range.
  - **`noBlockFound(from, to)`** names the requested points, never internal window points.
  - **I-Suppress:** the range's internal traffic never reaches the listener, nor the internal listeners of
    `N2NChainSyncFetcher` and `BlockfetchAgent`. That covers its ChainSync and the parent lookup. `BlockRangeSync`
    never emitted `onRollback` and still does not.
  - A halt stops window and header requests at once.
- **BlockFetch ownership** (review r4: Codex F14).
  - BlockFetch operations are kept in request order, each tagged with its purpose (public range, window, parent
    lookup, tip body, D4c) and its generation. Only windows are pipelined; every other operation runs alone, and an
    abandoned request stays counted until its reply has been drained.
  - An operation ends on `BatchDone` *or* `NoBlocks`. On both, Yaci resets `BlockfetchAgent`'s cursor and hands over
    to the next operation itself.
  - Every marker and error is routed to the operation that owns it.
- **Round trips** (measured and tuned in phase 2, #261).
  - Up to 2,000 header requests are in flight (`headersInFlight`), and never past `to`.
  - Up to two windows are requested at once (BlockFetch pipelining; replies match requests in order). Behind a
    window in flight, the next one is requested only when it is full (W, B, or up to the mutable zone or `to`), so
    the peer always has a full window queued and a window boundary costs no idle round trip.
  - Measured on a public relay at about 200 ms, mainnet, feature on against off, interleaved:
    - Conway segments: within 5% of today's path.
    - Byron: about 2× slower. A Byron header (about 700 B) is nearly as large as its block (750–1,050 B), so the
      windowed path moves about 1.75× today's bytes, and the relay answers headers slowly while it streams blocks.
      This adds about 3 minutes to a full mainnet sync from a public relay.
  - Local node (Musashi): equal to today's path.
  - How the Haskell node drives BlockFetch from ChainSync remains for #261.
- **D4b. Parent lookup.** Needed for a certifying block at the start of a windowed range.
  - ADR 0016 D4b's `FindIntersect` search runs on the main connection's ChainSync after step 2 has fetched `from`,
    and before header pipelining starts, under I-Suppress. ChainSync then re-intersects at `from`.
  - Its deadline and cancellation are the start's.
  - D4c keeps today's `ParentBlockLookup`.

### D5. One memory budget, counted in bytes

What Yaci holds for not-yet-delivered blocks is charged to one budget (default 32 MB, configurable). Credit is
reserved before a request is sent, moves with the data from stage to stage, and is released only when the retained
data is gone (review r2: Codex F7, Fable F8).

**Phase 2: the simple form** (r11). The staged form below comes with phase 3.
- One credit counter per start, in its delivery queue.
- Each queued entry is charged the wire bytes it retains: a block's bytes, a header marker's bytes, a parse error's
  bytes, and the EB transaction bytes as received by the resolver, carried with the completed block to its entry
  (r12). The resolver returns those bytes together with the completed block, and no EB source retains the block
  (r13).
- A window reserves its bytes before it is requested: the sum of its headers' `blockBodySize`, or the Byron average
  (D4). Its reservation is released per block once that block is queued; the entry then carries the charge. An
  abandoned window releases what it still holds.
- A reservation is granted while queued and reserved bytes stay within the budget. A request larger than the budget
  is granted only when nothing is queued (markers included) and nothing is reserved (r13). Nothing waits for credit.
- A reservation on a stopped, replaced or halted start is refused for good, and no wait is registered. A stop or
  halt drops a held request's wake-up instead of running it (r13).
- A refused reservation registers its wake-up in the same step, with its byte count. It is woken only by a credit
  change that admits that count, so a partial release causes no busy loop (r12).
- Validation: `LeiosConfig` fails fast unless the budget is > 0 and ≥ (k + 2) × 1,100 + `windowBytes`, computed
  overflow-safe.

**The staged form** (phase 3):

| Stage | Charge |
|---|---|
| Buffered headers | their bytes; at most `headersInFlight` (default 2,000) in flight, about 2.2 MB at the 1,100 B maximum header size; `headersInFlight` + W held, and never fewer than k + 2, which are always reserved for the mutable zone |
| BlockFetch window | the sum of its headers' `blockBodySize`, from request until each block is delivered or discarded; a Byron window reserves the average Byron block size seen so far, and each block is charged its real size once queued (no certifying block waits) |
| EB request | the size announced in the parent's header (`EbAnnouncement.size`), capped at 660 kB (the `MsgLeiosBlock` limit), until the EB arrives |
| EB transactions | the exact bytes the EB references, from request through raw reply, CPU mapping and queued block until delivery |
| Written but abandoned request | held until its reply is drained or the connection closes |
| Original block bytes kept for `onParsingError` | their size, until delivery |

- **Admission.**
  - Windows and header-triggered EB prefetches are admitted only under the budget, and prefetch may use at most half
    of it.
  - The front block's own fetch is always admitted. It has priority, so a full budget can never hold back the block
    that would free it.
- **Worst case:** the budget, or one oversized request granted on an empty queue with nothing reserved, plus the
  front block's EB.
- **Pipeline depth at the defaults** (review r3: Fable F18):
  - D3's 5 MB cap on expected reply bytes binds first. That is about 17 Musashi-average transaction replies (about
    0.3 MB each) plus many small EB requests; the fetch-only benchmark reached 141 in flight.
  - The prefetch half of the budget (16 MB) admits about 50 EBs of that size.
  - #261 tunes from this base.
- **D4c is outside the budget.** It starts after the queue has drained, and is today's path, with today's
  synchronous delivery and TCP backpressure; its EBs come over the side connection. It retains what today's path
  retains.
- **Header buffer:** k + 2 headers are always reserved, about 3 MB at k = 2,160, so verifying the mutable zone can
  always complete. Windows draw on the rest of the budget.
- **Validation** (review r5: reviewer3 R3-F7; r11). `LeiosConfig` fails fast unless the budget is positive and at
  least that header reservation, (k + 2) × 1,100 bytes, plus one window (`windowBytes`), computed overflow-safe.
- **Prefetch order.** Prefetched transaction requests are admitted in chain order, not in the order EB checks
  finish on the CPU pool. So a later block's large EB cannot take the prefetch half from the block after the front.
- **Singleton fetches** (`[from, from]`, `[P, P]`) reserve one block's maximum body size.
- **The budget counts wire bytes.** Deserialized blocks and transactions take several times more heap. Phase 3
  measures heap per budget byte and documents the recommended heap.
- **Measured in the POC:** Musashi from genesis to block 20,000 completes with a 512 MB heap, also with a listener
  slowed to 2 ms per block. The POC admitted windows on queued bytes, not on reservations, so this does not yet prove
  the bound above.

### D6. EB fetch starts from the header

- **When.** A certifying header (`leiosCertInBody`, ChainSync) whose parent's announcement is known starts the EB
  fetch before BlockFetch delivers the body. This is certified-only, as Haskell's roll-forward offer is.
- **Limit.** Bounded by D5, and by a maximum number of prefetched EBs (default 64).
- **Configurable,** default on for remote peers. Against a local node it measured no gain, because round trips there
  are under a millisecond.

### D7. Opt-in, then default

- **One predicate** enables D1–D6 and D10 for both the client and the source.
  - It is a `LeiosConfig` setting.
  - Its default can be set with a system property, so helpers built with the default-config constructors pick it up
    with no code change. yaci-store builds its helpers that way (review r2: Fable F12).
  - **Until D10 (phase 4) it changes only `BlockRangeSync`** (r11). `BlockSync` is unchanged with it on: today's
    threads, delivery and side connection.
- **Feature off: nothing changes,** on any network. Zero downstream impact holds by construction.
- **Feature on: listener callbacks are unchanged in every era.** The request pattern of `BlockRangeSync` does change:
  ChainSync headers plus BlockFetch windows instead of one `RequestRange`.
- **Default off for one release,** with the side-connection path kept as the fallback.
  - It becomes the default only after phase 5's exit criteria pass, mainnet throughput included. Then D8 applies.
  - yaci-store needs a Yaci 0.5.x build for Leios in any case.
- The pool's `MAX_READ_AHEAD` is untouched.

### D8. Remove the pool; keep the side connection only for D4c

Once D1–D7 and D10 are the default:
- **Remove** `PooledEndorserBlockSource`, the ADR 0017 D4 pool.
- **Keep** `EndorserBlockResolver`'s side `TCPNodeClient` and `ParentBlockLookup`'s connection only for D4c (owner
  decision, 2026-11-25).
  - They are created only while D4c runs, which is when a range endpoint is off the peer's selected chain, or after
    a fault.
  - They are not used for windowed ranges or for `BlockSync`.

### D9. Fair egress (#253): independent

#253 is implemented as specified: per-protocol round robin, one 12,288-byte SDU per turn. It is needed for Yano's
server side, and it is not a prerequisite for this ADR.

### D10. Tip following (`BlockSync`) on the same connection, paced by delivery

From phase 4, with the feature on, `BlockSync` does not use today's side connection (D8). Its
`N2NChainSyncFetcher` connection also registers the `PipelinedLeiosFetchAgent` (D3), and its callbacks go through the
delivery queue and the gate (D2, D1). Until then `BlockSync` is today's path, with the feature on or off (D7).

- **Today's ordering.** `Agent.addListener` inserts at index 0, so `N2NChainSyncFetcher`'s internal listener, added
  first, runs *last*. Today `BlockSync`'s `onBlock` therefore returns before the block is confirmed (ChainSync's resume
  point) and before `RequestNext` is sent.
  - Once `onBlock` only enqueues a waiting certifying block, that order would break.
  - D10 keeps it by moving confirm and `RequestNext` to the end of the block's delivery. This changes
    `N2NChainSyncFetcher`, a public class, only behind the D7 predicate (review r3: Fable F6).
- **One transition: the block's delivery concluded.** Its outcome decides what follows, as today:
  - **`onBlock` returned, a Byron callback was delivered, or a skip was delivered:** confirm the block, then
    `RequestNext`.
  - **`onBlock` threw, or the block failed to parse:** after its `onParsingError` is delivered, `RequestNext` without
    confirming, as today's `onParsingError` path does.
  - **Halt or stop:** neither.
  - **`NoBlocks` for a tip block:** as today.
- **Every `BlockSync` callback is queued in receive order:**
  - `rollforward` and `rollforwardByronEra`, `intersactFound`/`intersactNotFound`, `onRollback`;
  - `batchStarted`, the block callbacks, `batchDone`, `noBlockFound`, `onDisconnect`;
  - except the D1 start-up exception.

  With one block in flight, a rollback can be received only after the previous block's delivery concluded. So on an
  uninterrupted connection its position is today's.
- **Tip callbacks keep today's timing** (review r4: Codex F5; Fable F29).
  - `rollforward` and `batchStarted` are delivered when they arrive, before the block is ready, as today. Failed
    attempts then show as `RETRYING`, and a strict halt shows as `SYNC_HALTED` without `onBlock`, exactly as today.
  - `NoBlocks` for a tip body behaves as today.
  - Yaci remembers which of the pending block's callbacks have already been delivered.
  - After a private replacement:
    - **The same block:** the replay does not repeat `rollforward` or `batchStarted`, and the block is delivered once.
    - **A sibling:** when `FindIntersect([B, A])` lands at `A`, a public rollback to `A` is delivered first, then the
      sibling's callbacks. That is a normal ChainSync trace, and the same one today gives when a reconnect finds a
      different successor.
- **Reconnect while a block waits for its EB** follows D2's table for `BlockSync`. The internal
  `FindIntersect([B, A])` decides between today's trace (`B` found), a rollback to `A` and then the sibling (`B`
  orphaned), and `intersactNotFound`.
  - The resume position after "deliver `B`, roll back to a non-origin `A`, disconnect" is `A`. After a rollback to
    origin it stays `B`, as today's ChainSync records it (review r4: Codex F4).
- **Parent lookup.** The parent of the first block after an intersection or a rollback is always a known point: the
  intersection point, the rollback point, or the previous block.
  - It is fetched with BlockFetch `[P, P]` on the main connection, never delivered (I-Suppress), with no search.
  - The rollback point is recorded as the known parent (review r2: Fable F7).
- **EB availability at the tip.** The peer serves a certifying header only after adopting it, which needs the EB's
  transactions, so the EB is normally available.
  - The Haskell reference documents a race in which an honest peer prunes an offered EB before the request
    (`LeiosDemoLogic.hs`). That case goes through the normal budget, retry, skip and halt rules.
- **Bounded:** the queue holds at most one block plus its markers, so `BlockSync` needs no window.

## Alternatives rejected

- **A pool of side connections** (ADR 0017 D4 prototype). It breaks one connection per peer and multiplies relay load.
  It stays as a measurement baseline only. Pipelining on one connection reached the same speed in good periods
  (Evidence).
- **One `RequestRange(from, to)`, paused with `autoRead` off.** This deadlocks on a shared socket and stalls
  keep-alive. Measured: an unbounded BlockFetch on the shared connection made EB replies wait 25–34 s behind bulk
  block bytes.
- **Blocking the event loop when the queue is full.** This deadlocks once LeiosFetch shares the socket.
- **Fetching on an EB announcement.** It requests EBs that may never be certified (withdrawn in #255).
- **#253 alone.** It fixes sending, not the processing-side deadlock.
- **One connection even for a range off the selected chain** (r4: a slot horizon, a `[to, to]` probe and a by-hash
  tail). Review showed that it can publish blocks for a range that today's path answers with `NoBlocks`, and that its
  tail has no enforceable memory bound. The owner chose today's path for those ranges instead.
- **Delivering a pruned-EB disconnect as a connection loss.** It would add an `onDisconnect` and a same-point
  rollback, and so a yaci-store `rollback` row, where today nothing is visible. The owner chose to keep it private
  (V6).

## Evidence (POC, 2026-11-24 and 2026-11-25)

The POC is a local branch, `poc/leios-one-connection-pipelined`, on top of PR #256; it is not pushed. Its diff, the
`LeiosFetchBench` source and every run's summary and callback trace are kept with the author and can be attached to
#260 on request.

Peers, all Musashi `rc-2026w45` Haskell nodes:
- **Local:** a relay on the same machine as Yaci, `127.0.0.1:3101`.
- **Public:** the relay `52.29.179.71:3001`, about 240 ms TCP connect from Yaci.
- **Remote VM:** our own relay in a European data centre, reached through an SSH tunnel (155 ms RTT, about 11 MB/s
  tunnel ceiling). The VM itself is 8.5 ms from the public relay.

### LeiosFetch alone (certified EBs from block 9,200; identical bytes in every run)

| Peer | Config | EBs/s | MB/s |
|---|---|---|---|
| local | depth 1 (today's pattern) | 81–105 | 24–31 |
| local | **150 in flight, 5 MB cap (D3 defaults)**, 200 EBs | **103** | **30** |
| local | 150 in flight, no byte cap, 200 EBs | 85 | 25 |
| public | depth 1 (today's pattern) | 0.8–2.0 | 0.2–0.6 |
| public | depth 16, 1.2 MB cap | 10.2–11.4 | 3.0–3.3 |
| public | depth 16, 5 MB cap, 200 EBs | 23.7 | 6.9 |
| public | **150 in flight, 5 MB cap (D3 defaults)**, 200 EBs, 141 reached | **29.2** | **8.5** |
| public | 8 connections, depth 1 | 4.5–13.7 | 1.3–4.0 |
| remote VM via tunnel | depth 1 / depth 16 uncapped / 8 connections | 2.1–2.3 / 13.8–15.8 / 12.5–13.9 | |
| public, from the VM (8.5 ms) | depth 1 / depth 16 uncapped / 8 connections | 3.8 / 32.6 / 27.1 | 1.1 / 9.5 / 8.0 |

- **One pipelined connection matches or beats 8 connections** on every path measured.
- **Pipelining helps even near the relay.** From 8.5 ms away, depth 1 gave only 3.8 EBs/s: the relay takes roughly
  120 ms to answer a request (inferred from about 250 ms per EB at two requests per EB).
- **The 5 MB byte cap costs nothing,** and protects memory.

### Full sync with `BlockRangeSync` (`onBlock` and EB trace checked)

| Run | Range | Time |
|---|---|---|
| Local, today's path | genesis → 20,000 | 52–65 s |
| Local, one connection (D1–D6) | genesis → 20,000 | 42–55 s |
| Local, 9-connection pool | genesis → 20,000 | 46–49 s |
| Local, slow listener (2 ms/block): today / one connection | genesis → 20,000 | 88 s / 67 s |
| Local, one connection, 512 MB heap: normal / slow listener | genesis → 20,000 | 55 s / 68 s |
| Remote VM via tunnel, today's path | genesis → 11,000 | 4:06, 4:52 |
| Remote VM via tunnel, one connection | genesis → 11,000 | 0:48, 1:13; 1:01, 1:16 without header prefetch |
| Remote VM via tunnel, 9-connection pool | genesis → 11,000 | 0:53, 0:53 |
| Public, 2026-11-25 07:12 UTC: today's path / one connection / pool | genesis → 11,000 | 4:34 / 0:50 / 0:42 |
| Public, 2026-11-24 16:57–17:55 UTC (slow period): today's path | genesis → 11,000 | 14:05, 10:29 |
| Public, same slow period: one connection / pool | genesis → 11,000 | 14:30, 9:33, 4:31 / 2:38 |

- **Correctness:**
  - All 37 runs passed: no parsing errors, gaps, retries or missing EBs.
  - Every run's trace up to the stop block is byte-identical to today's path: 19 local runs at 20,000 (`7c018e3c…`),
    18 public and remote-VM runs at 11,000 (`3f1e0608…`).
    - The trace records each `onBlock` (number, hash, transaction count and digest) and each EB event.
    - Duplicates are counted separately, before the trace (review r3: Codex F9).
      - Every feature-on run and every pool run counted 0.
      - Three runs of today's path counted 1–2 (`vm-base-a`, `vm-base-b`, `pub-base-b`). They come from the
        checker's own outer retries, which start a new inclusive `fetch`, so they are not evidence for V1 (review
        r4: Codex F9). V1 needs a same-call forced-reconnect test (Tests).
    - The digest covers the accepted block entries.
    - It does not record `batchStarted`, `batchDone`, threads, rollbacks or disconnects, so it supports I2 for these
      runs, but not all of I1. Phase 4's full-callback recording is I1's gate (review r2: Codex F9, Fable F10).
- **Throughput, public relay outside the slow period:** one connection delivered about 220 blocks/s, 15,400
  transactions/s (EB transactions included) and 11 EBs/s, about 5.5× today's path, with one connection instead of
  ten.
- **Local:** Yaci on one connection keeps up with what the local node serves, about 400 blocks/s and 22 MB/s on
  Musashi, while that node is itself still syncing. A profile shows the remaining CPU goes to building the
  transaction model, not to the network: EB transactions on the CPU pool, ranking blocks on the event loop. Pool size
  (4/8/12 threads) made no difference.
- **The slow period** slowed every mode, today's path included (2–3× slower than the next day), so it lay on the
  relay or the path, not in the design. In it, the pool degraded least, because each TCP connection gets its own
  share of a congested path (inferred; loss could not be measured: the relay blocks ICMP).
- **A full sync keeps fewer EB requests in flight than the fetch-only benchmark:** it only fetches EBs for blocks
  within the delivery budget and header prefetch. Raising the depth a sync sustains is a tuning target (Plan, #261).

## Downstream contract

What a downstream relies on was checked against yaci-store, the main downstream, at commit `1ff6f5ed`. It pins Yaci
0.4.x today, uses no Leios callback, and builds its helpers with the default-config constructors.

- **Feature off (the default until phase 5):** nothing changes.
- **Feature on:** each item below is kept by the decision named, and the deviations are listed below the table.

No downstream code change is needed in either case; the D7 opt-in can be a system property.

| # | yaci-store relies on | Kept by |
|---|---|---|
| C1 | Callbacks for one listener are serialized, with happens-before between them. Its state (`syncMode`, epoch and era, the parallel-mode batch buffer) is not thread-safe. No thread identity is assumed. | D1: a windowed start's callbacks are serialized through its delivery queue, one drain at a time, and windowed starts on one listener are gated; D4c delivers synchronously after the drain; `BlockSync` as today until D10 (I1) |
| C2 | `fetch(from, to)` delivers every block of `[from, to]`, `from` included, exactly once, in chain order. It has no gap detection, so a skipped block would be lost silently. | D2 exactly once; D4 verified windows; I2, I-Range, I-Resume |
| C3 | `batchDone` arrives after the last block of the range. From inside it, yaci-store stops the delivering `BlockRangeSync` and starts `BlockSync` from its cursor. | D2 (`batchDone` is a marker that carries its range); D4 (one per range); D1 stop; I-Stop; the nested `BlockSync` start is not queued or gated (D1) |
| C4 | `onRollback` comes after every block received before it, and no block of the abandoned fork follows it. Its cleanup deletes by slot from the persisted cursor. Every `startSync` begins with a rollback to the intersection. | D2 markers in receive order; D10 pacing and resume point; I-Rollback |
| C5 | `stop()`, `restart()` and `startSync()` are called from callbacks and from other threads, inside yaci-store's own `synchronized` methods. | D1 stop: retire at once, close asynchronously, never wait for the loop or delivery; I-Stop |
| C6 | Blocking in `onBlock` is its only backpressure: synchronous DB writes, and in parallel mode a join of up to 5 minutes every 100 blocks. | A blocked listener holds its start's drain; windows reserve credit before they are requested (D5), so they stop at the budget, and nothing waits for credit; D4c starts after the queue has drained and delivers synchronously, as today; `BlockSync` as today until D10 |
| C7 | Health is the time since the last *block* callback (`blockReceiveDelaySeconds`, default 120 s); optional auto-recovery restarts the sync. | The 45 s front budget, with its deadline kept across replacements, and the skip/halt rules (D2); per-protocol stall clocks (D3) |
| C8 | A listener exception: yaci-store calls `stopSyncOnError()` and rethrows, and with `continueOnParseError` it publishes `onParsingError`'s bytes in `ErrorEvent`. | D2: `onParsingError` in place, with the original bytes; I-Stop's exception for it |
| C9 | `intersactFound`/`intersactNotFound` arrive even when no block follows. `BlockFinder` shares the `BlockSync` instance, waits up to 40 s for them, then stops it. | Today's `BlockSync` until D10; then D10 markers, and the fast path delivers at once |
| C10 | New callbacks would need no-op defaults. | No callback is added |
| C11 | `isRunning()` and the keep-alive getters are polled every 10 s, and shutdown checks `isRunning()` before `stop()`. | `isRunning()` is false as soon as `stop()` returns, even before the channel closes; during a genuine reconnect it behaves as today; during an internal replacement it stays true, and the keep-alive getters keep their values (D1, D3) |

**Deviations with the feature on.** Each is visible only in the case described, and each was reviewed against
yaci-store:
- **V1. No repeat after a reconnect inside a range.** Today such a reconnect delivers the last block and
  `batchStarted` a second time; now neither repeats (D2, exactly once). This also covers a block whose listener
  threw: it got its `onParsingError` and is not delivered again after a reconnect. yaci-store tolerates either.
- **V2 (withdrawn in r4).** An internal replacement is now private (D3), so it triggers no callback.
- **V3 (withdrawn in r5).** A range that is not a verified selected-chain path now uses today's path (D4c), so no
  partial delivery happens that today's path would not also produce.
- **V4 (withdrawn in r5).** A faulty peer's short batch now leads to D4c, today's own recovery.
- **V5. No overlap between windowed starts that share a listener** (D1). Today the new start's loop callbacks can run
  during the old start's callback. Between windowed starts they now wait for it to return. Only a listener that
  blocks inside a callback, waiting for another start's callback on the same listener, would notice. yaci-store does
  not: `BlockFinder` uses its own listener, and the restart runs on a virtual thread.
  - The `BlockRangeSync` → `BlockSync` handoff is not gated, so it overlaps as today until D10.
  - D4c's direct delivery is not gated either, as on today's path. So V5 does not hold while a range is on D4c
    (r13: Fable round 7 note).
- **V6. A remote close of the shared channel with a LeiosFetch request outstanding is private** (D3, owner decision).
  - A Haskell peer closes the connection on a request for an EB it has pruned. Today that loses only the side
    connection, which no downstream sees, and the private rule keeps it that way.
  - A genuine network loss at that moment is hidden too, but only while the reconnect succeeds within 15 s. After
    that, `onDisconnect` is delivered.
- **V7 (approved by the owner, 2026-11-25). A peer missing a body on its own selected chain.**
  - Example: a Yaci or Yano server with `blockFetchCheckRangeExists` on and a body missing in a later window.
  - Today's whole-range request is checked up front and gets `NoBlocks` with no other callback.
  - With windows, earlier windows are already delivered. The missing body is a verification fault, and D4c then
    gets `NoBlocks`, so the listener sees valid blocks, then `noBlockFound`.
  - An honest Haskell node never lacks a body of its selected chain. No windowing scheme can preserve the up-front
    check, because the whole range is not requested at once.

**Cursors.** Three cursors are involved, and none changes meaning:
- **Yaci's resume position** (D2). For a range, the last *queued* block. `BlockSync` keeps today's resume rules
  until D10; with D10, the last delivered block, rollback point or intersection point, with today's exceptions for a
  parse error and a rollback to origin. It is the only place a start resumes from (I-Resume).
- **yaci-store's committed cursor:**
  - in sequential mode, written per block before `onBlock` returns;
  - in parallel mode, written per batch of 100, so it can trail delivered blocks.
  - On restart, yaci-store rolls back to it and reprocesses its inclusive start.
  - Its behaviour is a function of the callback sequence alone. An unchanged sequence gives unchanged commits,
    including today's replay of a partial parallel batch after `batchDone`.
- **A fresh logical start** (`fetch` or `startSync`) never inherits the previous start's duplicate check (D2).

Blocks queued but not yet delivered when the process stops are fetched again from yaci-store's own cursor on restart,
as blocks in flight are today.

## Invariants

- **I1.** Listeners see the same callbacks, arguments and order as today, serialized per start: one drain at a time
  for a windowed start, D4c synchronously after the drain, and `BlockSync` as today until D10. The exceptions are
  deviations V1, V5, V6 and V7, and D1's start-up notifications on the caller's thread, as today.
- **I2.** Every block of a range is delivered exactly once, also across reconnects.
- **I-NoWait.** No Yaci code on a start's event loop waits for network I/O, and no producer waits for credit or for
  delivery. The one exception is D4c's synchronous completion over its side connection. Listener code may block, as
  today.
- **I-Budget.** What D5 counts stays under its budget, except for these stated overshoots:
  - a request larger than the budget, granted only when nothing is queued (markers included) and nothing is
    reserved (D5);
  - charges that were not reserved ahead, which are a Byron window's real sizes against its average estimate and
    parse-error entries;
  - in the staged form (phase 3), the front block's own fetch and a singleton fetch.

  D4c is today's path and retains what today retains.
- **I-Certified.** Only EBs announced by the parent of a block with `leiosCertInBody == true` are requested.
- **I-Generation.** No reply of one connection generation completes a request of another.
- **I-Range.** Every BlockFetch window delivers exactly its expected points, in order, or freezes the range at the
  last contiguous, verified block (D4). A range never delivers past a block that was not received and verified. It
  never delivers a mutable-zone block before that zone's headers have been verified to end at `to`.
- **I-Suppress.** Internal protocol traffic never reaches the downstream listener, nor the internal listeners of
  `N2NChainSyncFetcher` and `BlockfetchAgent`: the range's ChainSync, parent lookups, and the `[P, P]` fetches. An
  internal fetch never confirms a point or sends `RequestNext`.
- **I-Rollback.** A rollback is delivered after every entry received before it, and no block of the abandoned fork is
  delivered after it.
- **I-Stop.** `stop()` never waits for the loop or for delivery, also when called from a callback.
  - After it returns, `isRunning()` is false, no callback of that start is admitted, and the retired start's backlog
    is pruned at once. A callback already running may finish, as today.
  - After it returns, the start writes no further request: its reservations and its D4c direct grant are refused for
    good (D5, D4c).
  - Two exceptions are kept from today:
    - an admitted block's `onParsingError`, reporting an exception thrown by that running callback;
    - the retired start's `onDisconnect` when its channel closes, which today's assembler forwards for a replaced,
      non-halted start (review r3: Codex F5).
  - A halt is not a stop: delivery closes at the position of its `SYNC_HALTED` report, so everything queued up to and
    including it is delivered, and later entries are discarded (D2).
  - Closure is one state per queue: open, halted or closed (D1, r12). A `stop()` or replacement wins over a halt,
    also over a halt reported later. After closure no ordinary entry enters, even from a producer that already
    passed its own retirement check. Only the two exceptions above pass.
  - Callbacks of different windowed starts never overlap on a shared listener (D1, the gate). Outside the gate, as
    today: the nested start-up notifications on the caller's thread, D4c's direct delivery, and `BlockSync` until
    D10.
- **I-Resume.** A range resumes from its last queued block, with queued entries kept (D2). `BlockSync` keeps today's
  resume rules until D10. With D10 it resumes only from its resume position: the last delivered block, rollback point
  or intersection point, and its ChainSync resume point moves only when a delivery concludes, as D10 lists per
  outcome.
- **I-Private.** A private replacement (D3) changes nothing the listener sees. The exceptions are a genuine rollback
  to an earlier point, the public rollback delivered before a sibling of a pending block (D10), and
  `intersactNotFound`.

## Tests

**Unit, through real `BlockfetchAgent`, `ChainsyncAgent`, `Session` and `MiniProtoClientInboundHandler` state, not
stubs:**
- **Range classification (D4 step 1), with a static database for each:**
  - both endpoints on the selected chain;
  - `to` on a retained fork, with `from` on the selected chain or on the fork;
  - a fork whose older predecessor file was collected (today: `NoBlocks`; D4c gives the same);
  - an unknown `to`;
  - reversed bounds;
  - equal slots with different hashes;
  - `fetch(P, P)`;
  - a public `fetch(P, P)` right after an internal `[P, P]`;
  - a failed internal fetch followed by a valid public fetch (ownership on `NoBlocks`).
- **reviewer3 round 1:**
  - a genuine reconnect while a certifying front waits: today's exact trace, with no rollback of a delivered block;
  - `BlockSync` reconnect with the front orphaned (found at `A`), pruned with a close, and not found; skip expiry
    while the `FindIntersect([B, A])` is in flight;
  - D4c entry with a retained front: front completes before the fresh connection opens;
  - a parse error in step 2's internal fetch: whole range on D4c;
  - skip expiry during a `BlockRangeSync` reconnect: no duplicate;
  - a pruned EB behind a healthy front: front isolation, and the front completes;
  - D4c entry while windows and pipelined headers are in flight: fresh connection, then the next range;
  - a reorg between the two intersections: no `batchStarted` before `noBlockFound`;
  - a resume at a queued certifying block: no re-fetch or EB lookup;
  - a Byron mutable zone with an epoch-boundary block;
  - a 15 s promotion, then a successful reconnect: public re-intersection;
  - `LeiosConfig` validation.
- **Delivery queue (phase 2 review round 5, r11):**
  - a lost gate wake-up: a start that registers as a waiter while the holder exits is still rescheduled;
  - gate fairness: two windowed starts on one listener alternate, one entry per turn, while both have entries;
  - terminal identity: a `fetch()` from a stale `batchDone` of an earlier range is not admitted;
  - an `Error` thrown by a listener: logged, and later entries are still delivered;
  - a strict halt queued behind a blocked callback: the prefix and its `SYNC_HALTED` report are delivered, nothing
    after;
  - D4c starts only after the queue has drained, then delivers synchronously;
  - `BlockSync` with the feature on equals the feature off, callback for callback and thread for thread.
- **Delivery queue and D4c entry (phase 2 review round 6, r12):**
  - the fresh D4c connection lost while the queue drains (handshake held until after the drain): the range stays at
    D4c entry, and the next connection repeats it;
  - a refused reservation after a partial credit release: no busy loop, woken once the count fits;
  - `stop()` after a halt boundary, and a halt reported after `stop()`: the stop wins;
  - EB transaction bytes charged with the CBOR flags off;
  - delivery mode per range: the range after a direct D4c delivers through the queue;
  - gate turns: first come, first served, and a pruned waiter passes its turn on;
  - the drain quantum: a busy start gives up its pool thread every 64 entries or 50 ms;
  - an enqueue after closure is rejected.
- **Closed queues and EB bytes (phase 2 review round 7, r13):**
  - a stop between D4c's last checks and the direct grant: the grant is refused, and no request is written;
  - a stale idle notification against a fresh grant: the turn stays with the start that has a backlog;
  - a queue holding only markers, with a request larger than the budget: refused until the last marker is removed;
  - the EB sources retain no completed block.
- **Anchoring and zones:**
  - a chain switch inside the server's handling of the first `FindIntersect` (`k = 2`): no block that is not an
    ancestor of `to` is published (review r4: Codex F18);
  - a historical `to`, Byron equal heights, and a header stream that parks before `to`: D4c takes the rest;
  - `k = 2,160` with `to` at the tip: the mutable zone verifies with bounded retention and completes;
  - a peer with the range-exists check on and a missing interior body (V7).
- **Mutable zone:**
  - a chain switch between the two intersections, and between the intersection and the first `RequestNext`: no block
    of the new branch is delivered;
  - headers passing `to.slot` without `to`: D4c takes the rest;
  - the seam into D4c, uninterrupted and with a disconnect on either side: no duplicate.
- **Range faults:**
  - a missing body in the second window, with later headers already buffered (no later block delivered);
  - an early `BatchDone` in a non-final window;
  - a window with a wrong point;
  - a fault near the start of a long historical range with a waiting front (retained bytes stay bounded);
  - a fault followed by D4c, with its side connection, and back to normal for the next range;
  - a disconnect mid-range: windows resume after reclassification, with no D4c (review r4: Fable F26);
  - the D4c seam with a certifying front and an in-flight prefetch; `stop()` during the side handshake;
  - a rollback while a window is in flight, with its late bodies and EB completions arriving after the freeze.
- **Byron:** headers; a range crossing Byron → Shelley; the two-block range of a regular block and its epoch-boundary
  successor.
- **Markers:** a disconnect, and a stop from inside a callback, between `StartBatch` and the first body
  (`batchStarted` once).
- **BlockFetch ownership:** a cold parent lookup with the child's `BatchDone` delayed.
- **Reconnects:**
  - before the first block (`from` delivered once, `batchStarted` once);
  - a peer that disconnects on every EB request: today's skip or halt within the budget, in strict and skip modes;
  - repeated replacements, and expiry while disconnected;
  - an idle tip for more than 15 s with no keep-alive: no replacement;
  - a stalled window with healthy keep-alives: replaced (per-protocol clock);
  - an internal replacement in `BlockSync`: no `onDisconnect`, no same-point rollback, no `rollback` row;
  - a private replacement whose resume point is gone: `intersactNotFound` delivered;
  - a pruned-EB remote close against a genuine loss at the same point (V6), and a private close that does not
    reconnect within 15 s (`onDisconnect` delivered);
  - a main close with no shared EB outstanding (genuine); a D4c main close while its side EB is outstanding
    (genuine);
  - tip traces with today's timing: retry then success, strict halt, skip, `NoBlocks`, and stop from `rollforward`;
  - a private replay of the same block (no repeated `rollforward` or `batchStarted`), and a sibling (public rollback
    first);
  - skip expiry after the private same-point rollback and before the sibling header: a public rollback comes
    before the sibling;
  - deadline against rollback in both orders, in skip and strict modes: no abandoned block after its rollback;
  - `deliver B → rollback to origin → disconnect` (resume point `B`, as today);
  - a silent tip peer, before and after `MsgAwaitReply`, with keep-alives running;
  - with a non-empty queue behind a waiting certifying front;
  - after the last block before `BatchDone`;
  - while idle, then an overlapping range.
- **Agent edge cases:**
  - an abort followed by a submit and a late reply;
  - a write before the handshake;
  - credit held by an abandoned written request.
- **Stall watchdog:** a listener blocking for more than 15 s (no false stall); a header window stalled after earlier
  block progress (a real stall).
- **Deadlines and races:** a front deadline during a long outage; a late success against the deadline; stop racing a
  ready front.
- **Memory:** EB bytes retained behind a slow front; `NoBlocks` mid-range.
- **The ADR 0017 D4 list:** ordering, halt and skip, listener exceptions, and `fetch()` in `batchDone`.
- **`BlockSync` (D10):**
  - a certifying block waiting across a reconnect (I-Resume);
  - `startSync` whose first block is certifying;
  - a certifying block right after a rollback (parent = the rollback point);
  - parse-continue, skip and halt as delivery outcomes.

**Downstream contract (C1–C11), with a listener shaped like yaci-store's `BlockFetchService`:**
- a `synchronized` service monitor;
- `stop()` of the delivering `BlockRangeSync` and `BlockSync.startSync()` from inside `batchDone`;
- a replacement start while the old start's callback is still inside the listener, including an exception thrown
  after the replacement;
- `isRunning()` polled, `fetch()` called and `stop()` called between logical retirement and the physical close: no
  request written;
- nested start-up: the new start's `onDisconnect` re-entrant inside `batchDone`, as today; a `batchDone` that throws
  after `startSync` (not translated into `onParsingError`);
- `BlockSync` resume schedules through the real listener registration order: a parse-continue then a disconnect; a
  delivered `B`, a rollback to `A`, then a disconnect;
- an external `stop()` racing that `batchDone` on the service monitor, run under barriers (no deadlock);
- an external `stop()` while the front waits for an EB, then a restart: no stale callback afterwards;
- the real shared `BlockSync` used by a `BlockFinder`-shaped intersection check, with both intersection outcomes and no
  following block;
- a listener exception in `onBlock` with `continueOnParseError` on and off and `returnBlockCbor=false`: the payload,
  and the callbacks after the stop;
- a listener blocking for seconds: window reservations are refused at the budget, so window requests stop, while
  keep-alive is still answered; memory stays under the budget;
- a recorder at listener entry and exit, recording:
  - every callback kind, its arguments, its start generation and its thread;
  - overlaps and duplicates, none filtered;
  - compared with today's path.

**Live:**
- the full-callback recording, feature on against feature off, locally and on the public relay, for `BlockRangeSync`
  and for `BlockSync`, including the switch at the tip;
- a full run to the tip;
- `yaci-sync-check` on preview, preprod and mainnet with the feature on (the Byron path and no regression);
- a slow-listener run with a fixed heap;
- yaci-store (Yaci 0.5.x build) on Musashi, feature on against feature off, with parallel processing on:
  - the same callback sequence and the same *commit* sequence (cursor writes, per-table rows, `rollback` rows and
    events);
  - a run that ends one block past a parallel batch boundary;
  - a restart with a partial batch;
  - a recovery restart during a stalled header window;
- Yano/Haskell sync QA for D9.

## Plan

| Phase | Work | Exit criterion |
|---|---|---|
| 0 | Merge PR #256 D1 (timings) and D2 (keep-alive). Keep the D4 pool off by default, as a baseline. | Done in #256 |
| 1 | `PipelinedLeiosFetchAgent` with generations, the handshake gate and credit for abandoned requests (D3); unit tests | Agent tests; local and public LeiosFetch benchmark at 150 / 5 MB |
| 2 | Logical range (D4): classification, two zones with mutable-zone verification, freeze and D4c continuation, BlockFetch ownership, I-Suppress, Byron by count. Pulled in (r10, r11), for windowed starts only: the pinned loop per start, stop with pruning, the halt position (D1); the delivery queue on a delivery thread, with the gate, terminal identity and Throwable handling (D2); D4c after the drain; the simple budget with reservations and validation (D5). `BlockSync` unchanged | Range tests; delivery-queue tests (round 5 list); `BlockSync` on equals off; `yaci-sync-check` preview, preprod and mainnet unchanged |
| 3 | Completes the queue and gate for windowed starts: certifying blocks wait in the queue, with the commit rule, the front deadline and the reconnect rule (D2), over LeiosFetch on the main connection; staged budget (D5); stall watchdog (D3) | Delivery and reconnect tests; slow-listener and fixed-heap runs; heap per budget byte |
| 4 | Header-triggered prefetch (D6); parent lookups (D4b, D10); `BlockSync` on one connection, routed through the queue and the gate (D10) | Downstream contract tests (C1–C11); full-callback recording identical except V1, V5, V6 and V7; yaci-store run on and off |
| 5 | Default on after one release; remove the pool, keep the side connection for D4c only (D8); update ADR 0016 and 0017 status | Full Musashi run to the tip; mainnet sync check; mainnet blocks/s from a local node within 5% of today's path |
| ‖ | #253 fair egress (D9) | #253's tests; Yano QA |
| Later | Parse ranking blocks off the event loop, in parallel, with the same ordered delivery (a general Yaci speed-up for local and LAN sync, mainnet included); its own ADR | Mainnet sync from a local node: blocks/s and CPU |

## Consequences

- **Downstream:**
  - No API change. With the feature off nothing changes. With it on, the contract yaci-store relies on is kept item by
    item, with deviations V1, V5, V6 and V7 (Downstream contract).
  - Enabling it during the opt-in release needs a system property, or one `LeiosConfig` option, and a Yaci 0.5.x
    build of yaci-store. After phase 5, nothing.
- **Network:** one connection per peer during Leios sync, instead of one main connection plus one side connection
  (or nine with the ADR 0017 pool). Fewer TCP handshakes and less relay load.
  - The exception is D4c: a range whose endpoint is off the peer's selected chain, or the rest of a range after a
    fault, uses today's path and its side connection.
- **Code:**
  - `BlockRangeSync` gains a header-driven windowed path, and `CompleteBlockAssembler` a non-blocking queue.
  - `N2NChainSyncFetcher` confirms and paces by delivery when the feature is on (D10, phase 4).
  - Each windowed start pins one event loop and has its own delivery queue; `BlockSync` does with D10.
  - Phase 5 removes the pool. The side connection and `ParentBlockLookup` stay, used only by D4c.
- **Performance:**
  - On a local node, about 15–25% faster in the POC, and about 30% with a slow listener. The node and Yaci's
    transaction model building are the limits after that.
  - On a public relay, about 5.5× today's path outside slow periods, and equal to the pool, with one connection
    instead of ten (Evidence). In a slow period it is at the mercy of one TCP flow (see Risks).

## Risks and open questions

- **One failure domain.** A stuck or invalid LeiosFetch reply now replaces the main connection. Mitigated by:
  - certified-only requests;
  - byte-based stall detection;
  - the resume position: the last queued block of a range.
- **Public relays.** A single connection is at the mercy of one TCP flow on a long path.
  - Measured: on a steady path it matches the pool (remote VM, and the public relay on 2026-11-25). In the slow
    period of 2026-11-24 it was several times slower than the pool, and today's path was slow too.
  - Haskell answers this with several *peers*, each one connection. A Yaci option to spread EB fetches over several
    relay addresses, one connection per peer, could recover it, since Musashi's name resolves to 8 relays.
  - Low priority; not part of this ADR.
- **Listener time** still bounds a slow downstream: on the delivery thread for a windowed start, and on the loop for
  D4c and, until D10, `BlockSync`. That is unchanged and intended (I1).
- **Design-only parts.** The POC covered `BlockRangeSync` in the Shelley-based eras, with no reconnect and no
  rollback. These have no live evidence yet:
  - D10, the switch at the tip (C3), C4, C5 and C9;
  - the reconnect rule and fault recovery;
  - Byron windows;
  - the classification paths and the mutable-zone verification.

  Phase 4's full-callback recording and the yaci-store run are their acceptance gates.
- **D4c is a performance cliff.** It runs at today's speed, with a second connection. It is used when a range
  endpoint is off the peer's selected chain, which can happen with an orphaned tip or with a relay behind the one
  `TipFinder` used (Musashi's name resolves to 8 relays), or after a verification fault. It is logged once per range
  and counted in a metric.
- **Open:**
  - window and budget defaults on mainnet;
  - the Byron gap on public relays (about 2×, see D4 Round trips);
  - CIP-0164's multi-EB request, once relays support it.

## Related findings (out of scope)

- **yaci-store, parallel mode.** `batchDone` does not flush a partial batch, so the switch to `BlockSync` replays its
  blocks from the committed cursor. This is pre-existing, and unchanged by this ADR.
- **A pre-existing stop race.** Today `Session.dispose` waits on `close().sync()`. An external stop that holds
  yaci-store's service monitor can wait on a loop that is itself blocked in `batchDone` on that monitor. D1's
  asynchronous close removes this race when the feature is on.
- **`BlockSync` stalls on `NoBlocks`.** On `NoBlocks` for a tip block, `BlockSync` sends no further `RequestNext`
  today. This is unchanged.

## Revision history

- **r1** (2026-11-25): initial proposal, with the POC evidence and the findings of two independent reviews of the
  POC folded into D1–D8 and Tests.
- **r2** (2026-11-25): D3 defaults stated as Haskell's 150 requests / 5 MB, with the measurement, and the depth
  reached is logged; new D10 (tip following in `BlockSync` on one connection, paced by delivery); new Downstream
  contract section (C1–C10, checked against yaci-store) with I-Rollback, I-Stop, I-Resume and their tests; Evidence
  adds the remote-VM runs, a second public series and the 150/5 MB benchmark (37 runs, traces identical); Risks and
  Consequences updated: on a steady path one connection matches the pool.
- **r3** (2026-11-25, responds to the reviews of `00b39067` by Codex, F1–F10, and Fable, F1–F13):
  - **Range semantics.** D4 rewritten: validation of `to` and `from` before any callback; the immutable-horizon tail
    when `to` is off the selected chain; verified windows (I-Range); a fallback after an anomaly or rollback; Byron
    windows by count; I-Suppress (Codex F1, F2; Fable F3, F4).
  - **Threads.** D1 pins one event loop per start, keeps the start-up `onDisconnect` on the caller's thread, and makes
    stop asynchronous and non-waiting (Codex F3, F5; Fable F5).
  - **Reconnect and tip following.** D2's reconnect rule discards undelivered entries, with I-Resume. D10 rewritten:
    today's ordering corrected, one delivery transition for confirming and pacing, every callback queued, the
    reconnect sequence, parent lookups with `[P, P]`, EB availability at the tip qualified (Codex F4; Fable F1, F2,
    F6, F7).
  - **Memory.** D5 staged reservations, with the header cap, the worst case, the heap note and the POC caveat (Codex
    F7; Fable F8).
  - **`onParsingError`** keeps its original bytes; C8 corrected (Codex F8; Fable F9).
  - **Stalls.** The stall rule covers every request, with a clock that excludes listener time (Codex F10; Fable F13).
  - **Downstream contract.** C7 and C8 corrected; C11 added; deviations V1–V3 listed; three cursors distinguished;
    yaci-store's commit sequence is now compared (Codex F6; Fable F11, F13).
  - **Opt-in.** A system-property default, so yaci-store needs no code change; zero impact stated for feature off
    (Fable F12).
  - **Evidence.** The trace's claim is narrowed, design-only parts are listed, and phase 4 is gated on a
    full-callback recording (Codex F9; Fable F10).
  - **Plan.** A mainnet throughput gate before default on; Related findings added; stale D-ranges fixed (Fable F13).
- **r4** (2026-11-25, responds to the reviews of `67619efa`: Fable round 2, F6 and F14–F20; Codex round 2, F1–F5,
  F7, F9, F10 reopened, and F11–F16):
  - **Range classification.** D4 step 1 classifies every endpoint case before the first public callback: a retained
    fork is served as today; an unknown `to` gives `noBlockFound` alone; a `[to, to]` probe settles a long range
    whose `to` is off the selected chain. V3 is narrowed to a pruning race (Codex F1).
  - **Faults.** Windows freeze at the first discrepancy and recover by reconnecting from the resume position. No
    by-hash suffix is sent. Three faults with no progress give V4 (Codex F2, F7; Fable F17).
  - **Handoff.** Callbacks of different starts never overlap; start-up behaviour on the caller's thread is
    explained; `isRunning()` is false at retirement (Codex F3, F16; Fable F16).
  - **Resume position** covers rollback and intersection points. D10's ordering is corrected, and a parse error does
    not confirm a block, as today (Codex F4; Fable F6).
  - **Internal replacement is private.** V2 is withdrawn, I-Private added, and the retired start's `onDisconnect`
    is kept as today (Codex F5).
  - **The front's deadline** survives replacement, and expiry can happen while disconnected (Codex F11; Fable F14).
  - **Per-protocol stall clocks**, with parked ChainSync exempt (Codex F12; Fable F15).
  - **Byron.** Epoch-boundary termination by point (Codex F13; Fable F19).
  - **BlockFetch ownership** by operation, and `batchStarted` tied to the wire `StartBatch` (Codex F14, F15).
  - **Budget.** EB request charge uses the announced size; pipeline depth at the defaults is stated (Fable F18).
  - **Smaller fixes.** Duplicate counts corrected in Evidence (Codex F9); V3's store outcome without auto-recovery
    (Codex F10); the duplicate check is armed only after a delivery (Fable F20).
- **r5** (2026-11-25, responds to Fable round 3, F21–F25, and Codex round 3: F1, F3–F5, F7, F9, F12, F14 and F16
  reopened, and F17–F20; two owner decisions):
  - **D4 rewritten around a verified path** (owner decision).
    - Windows run only when both endpoints are on the peer's selected chain. Blocks above `tip − k` are published
      only after their headers have been verified to end at `to`.
    - Every other range, and the rest of any range after a fault, uses today's path, D4c, with its side connection.
    - The slot horizon, the probe and the by-hash tail are gone, and V3 and V4 are withdrawn.
    - Seam duplicates are dropped by hash.
    - Covers Codex F1, F7, F17 and F18, and Fable F21 and F23.
  - **Remote closes.** A remote close with a LeiosFetch request outstanding is private (owner decision; V6, Codex
    F20).
  - **Deadlines and rollback.** The retained front stays at the head, ahead of `onDisconnect`. A rollback or a
    different successor supersedes it atomically (Fable F22, Codex F19).
  - **`BlockSync` callbacks.** One block's callbacks are released together, and `intersactNotFound` is delivered
    after a private replacement (Codex F5, Fable F24).
  - **Smaller fixes.**
    - The resume point is kept on a rollback to origin, as today (Codex F4).
    - Tip `FindIntersect` and `RequestNext` arm the ChainSync clock until their first response (Codex F12).
    - `NoBlocks` ends a BlockFetch operation (Codex F14).
    - A retired start admits no `fetch` (Codex F16).
    - Nested start-up behaves as today, and the cross-start gate is listed as V5 (Codex F3, Fable F25).
    - The Evidence duplicate counts are no longer credited to V1 (Codex F9).
    - D8 keeps the side connection for D4c only.
- **r6** (2026-11-25, responds to Fable round 4, F26–F30, and Codex round 4: F1, F3–F5, F9, F18 and F19 reopened,
  and F21–F24):
  - **D4 anchors on `to`'s own header,** fetched as an internal `[to, to]`. The final zone is
    `F = to.blockNo − k`, so it no longer depends on a tip read at another moment. The same header gives the request
    count (Codex F18, F23).
  - **Faults.** A connection fault re-classifies and continues windowed; only a verification fault, or leaving
    `to`'s chain before `to`, goes to D4c (Fable F26).
  - **D4c specified:** threads and side I/O loops, the I-NoWait exception, entry drain, the retained front handed
    over with its deadline, the watchdog off, resources and `noBlockFound` arguments (Codex F21; Fable F27).
  - **Header reservation.** k + 1 headers are always reserved (Codex F22; Fable F30).
  - **Classification.** The equal-slot rule is unambiguous (Codex F1). V7 is added for a peer missing a body of its
    own selected chain; the owner approved it on 2026-11-25.
  - **Closes.** Each is classified per channel. V6 applies to the shared channel only, and turns genuine after 15 s
    without a reconnect (Codex F24; Fable F28).
  - **Tip callbacks keep today's timing,** with replay suppression and a public rollback before a sibling. Expiry
    during a replacement re-intersects at the committed block. D2 and D10 now share one reconnect rule (Codex F5,
    F19; Fable F29).
  - **Smaller fixes.**
    - Marker-callback exceptions are not translated into `onParsingError` (Codex F3).
    - The cursor summary uses D2's resume position (Codex F4).
    - D2 no longer cites the baseline runs (Codex F9).
    - The Supersedes line is corrected, and the D4c performance cliff is added to Risks (Fable F30).
- **r6.1** (2026-11-25): V7 marked as approved by the owner. I-Private lists the public rollback before a sibling.
  Fable's final check of r6 (`8cb6fb07`): APPROVED.
- **r7** (2026-11-25, responds to reviewer3's round 1 on `8cb6fb07`, R3-F1 to R3-F7):
  - **Reconnects.** After any reconnect, the retained front is resolved before any re-intersection, giving today's
    exact trace. This replaces r6's rule for expiry during a replacement (R3-F1, R3-F5).
  - **D4c.** It always starts on a fresh main connection, so nothing is mid-exchange; the seam duplicate is dropped
    before EB resolution (R3-F2, R3-F5).
  - **Front isolation** after a LeiosFetch close or a retry (R3-F3).
  - **Step 2** fetches `from` and `to` internally, so D4b runs and `batchStarted` comes only before the first
    published block; a windowed resume skips `[R, R]` (R3-F4, R3-F5).
  - **Byron.** `difficulty`, with epoch-boundary blocks in the mutable zone and a k + 2 reservation; otherwise D4c
    (R3-F6).
  - **Promotion** after 15 s is final; `LeiosConfig` validation; prefetch in chain order (R3-F7).
- **r8** (2026-11-25, responds to reviewer3's round 2 on `c4c85ec8`):
  - **R3-F8.** Front-first resolution is kept for `BlockRangeSync`. For `BlockSync` a reconnect starts with an
    internal `FindIntersect([B, A])`, and its reply table decides today's trace, a rollback to `A` then the sibling,
    or `intersactNotFound`. Skip expiry during it is covered, and the "re-received copy" text is removed.
  - **R3-F9.** D4c entry order is fixed: retire, complete the front, connect and handshake, request.
  - **Minor.** A parse error in step 2 sends the range to D4c.
- **r8.1** (2026-11-25): reviewer3 round 3 on `cc0b87ef`: APPROVED. Its implementation note is applied to D4c entry
  (a halt skips steps 3–4; a front at `to` ends the range).

- **r9** (2026-11-26): phase 2 throughput tuning recorded (#261).
  - D4 windows: 2 MB / 2,000 blocks, two in flight, the full-window rule, Byron sizing by observed bytes, 2,000
    headers in flight.
  - D5: header and worst-case rows updated.
  - The measured Byron gap on public relays is added to Open, and the second-window question is closed.
- **r10** (2026-11-26): aligned with the implementation, after phase 2 review round 4.
  - **Delivery thread.** Callbacks run serialized per start on a delivery thread, not on the protocol loop. The gate
    reschedules a drain instead of waiting. Backpressure is the D5 budget; D4c and pre-D10 `BlockSync` wait on their
    own thread above twice the budget (D1, C6, I1). That wait is withdrawn in r11.
  - **Queue across reconnects.** A range keeps its queued entries across a reconnect and resumes from the last queued
    block. This is review r1's option (b), and V1 now also covers a block whose listener threw (D2, I-Resume).
- **r11** (2026-11-26): aligned with the implementation after phase 2 review round 5 (`4afd1082`), and contradictions
  the reviewer found removed.
  - **Scope cut.** With the feature on, only `BlockRangeSync` changes for now. D1's delivery thread, the queue and the
    gate apply to windowed starts; `BlockSync` keeps today's threads, delivery and resume rules until D10 (phase 4).
    V5 applies between windowed starts only; the `BlockRangeSync` → `BlockSync` handoff overlaps as today (D1, D2,
    D7, D10, C1, C3, C9, V5, I1, I-Resume).
  - **No producer wait.** The wait above twice the budget is withdrawn. Windows reserve credit before their request,
    and nothing waits for credit, so keep-alive always proceeds outside D4c (D1, C6, I-NoWait).
  - **D4c after the drain.** D4c waits until the queue has drained, then delivers synchronously on the start's loop
    until the next range is admitted (D4c entry, steps 4–5).
  - **The gate.** Waiter registration and giving up the drain are one step; one entry per turn while another start
    waits; a drain woken with nothing to deliver wakes the others; D4c and the start-up notifications are not gated
    (D1).
  - **Stop and halt.** No callback is admitted after `stop()` returns, and the backlog is pruned at once. A halt
    closes delivery at its `SYNC_HALTED` report, delivering what was queued up to it (D1, D2, I-Stop).
  - **Throwables.** A block's `Exception` becomes its `onParsingError`, a marker's failure goes to channel error
    handling, an `Error` is logged, and the drain continues (D2).
  - **Terminal identity.** `batchDone` and `noBlockFound` carry the range they end; `fetch()` from inside `batchDone`
    checks it (D2).
  - **Budget.** Phase 2's simple form: one credit counter per start, entries charged their retained wire bytes,
    windows reserved before the request and released per queued block, one window always granted on an empty queue,
    and validation of the budget, overflow-safe (D5, D4 admission, I-Budget).
  - **Text consistency.** "Delivered" corrected to "queued" for a range's resume and freeze positions (D2, D4,
    Cursors); V1 covers a block whose listener threw; phase 2 notes that the commit rule and front deadline come with
    phase 3; reconnect notifications enter the queue; the pinned loop, queue and simple budget moved to phase 2 in
    the Plan, and `BlockSync` through the queue is phase 4; round-5 tests added and the slow-listener test made
    reservation-based.
- **r12** (2026-11-26): aligned with the implementation after phase 2 review round 6 (`8877ece3`).
  - **Drain quantum.** A drain run delivers 64 entries or 50 ms, then resubmits itself to the back of the pool's
    queue; 64 callbacks blocked on distinct listeners occupy the whole pool (D1).
  - **Gate turns.** A freed turn is kept for the first waiting start (first come, first served); a start whose kept
    turn finds nothing to deliver passes it on (D1).
  - **Closure.** One state per queue (open, halted, closed) decides what enters and what is delivered. A `stop()` or
    replacement wins over a halt, also a later one; nothing ordinary enters after closure (D1, D2, I-Stop).
  - **D4c entry.** A loss before the request invalidates the drain wait and keeps the range at D4c entry. Direct
    delivery and the request are one checked transition on the loop, and the transition that first starts a range
    sets queued delivery for it (D4c).
  - **Budget.** EB transaction bytes are charged as received by the resolver; a refused reservation registers its
    wake-up with its byte count in the same step (D5).
  - **Tests.** Round-6 cases added.
- **r13** (2026-11-26): aligned with the implementation after phase 2 review round 7 (`0b043bc3`).
  - **Closed queues grant nothing.** A reservation on a stopped, replaced or halted start is refused for good, with no
    wait registered, and a stop or halt drops a held request's wake-up. D4c's direct grant needs an open queue as
    well as a drained one, so a start stopped after the range's checks sends nothing (D5, D4c).
  - **Oversized requests.** A request larger than the budget is granted only when nothing is queued, markers
    included, and nothing is reserved (D5, D4 admission, worst case, I-Budget).
  - **EB bytes** are returned together with the completed block; no EB source retains it (D5).
  - **Gate.** A start passes on an empty kept turn only after rechecking, under the gate's lock, that no backlog
    arrived (D1).
  - **V5** does not hold while a range is on D4c, whose direct delivery is not gated (Fable round 7 note).
  - **Tests.** Round-7 cases added.
