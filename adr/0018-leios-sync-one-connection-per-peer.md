# ADR 0018: Leios sync on one connection per peer: pipelined LeiosFetch, request-level flow control, in-order delivery

- Status: **Proposed** (design and plan, backed by a measured proof of concept; not yet implemented on `next`)
- Date: 2026-11-25
- Tracking issue: #260. Follow-up: #261 (measure the header overhead of BlockFetch windows, compare with the Haskell
  node, tune the defaults).
- Relates to: #255 (Leios sync speed), #253 (fair mux egress), PR #256 (ADR 0017: D1 timings, D2 side-connection
  keep-alive, D4 read-ahead prototype with a connection pool)
- Supersedes, once implemented: ADR 0016 D3b (synchronous delivery), D4a (LeiosFetch side connection) and D4b's
  separate lookup connection; ADR 0017 D2 (side-connection keep-alive) and the D4 pool.

## Context

To deliver a certifying block (a Dijkstra block with `leiosCertInBody == true`), Yaci must first fetch the endorser
block (EB) announced by the block's parent, and then the EB's transactions. Today, `CompleteBlockAssembler.onBlock`
does this inside the listener call, on the main connection's Netty event loop, over a separate side connection
(ADR 0016 D3b, D4a). That costs two round trips per certifying block, one block at a time:

- **Full Musashi run:** 129,229 blocks, 44,683 certifying, 61.8 M EB transactions, 9 h 21 min (1.33 EBs/s).
- **Where the time goes:** about 89% of wall time is spent waiting on EB fetches (ADR 0017 Stage 0).

### Constraints

1. **Downstream contract unchanged.** `BlockChainDataListener` receives the same callbacks, with the same blocks and
   transactions, in the same order and positions, serialized on an event loop as today:
   - `onBlock` with complete blocks;
   - one `batchStarted` and one `batchDone` per `fetch(from, to)`;
   - in `BlockSync`: `rollforward*`, `intersactFound`/`intersactNotFound`, `onRollback`;
   - `onParsingError`, `onEndorserBlockUnavailable`, `onDisconnect`;
   - halt and skip.

   Every block is delivered exactly once, with no gap. yaci-store, the main downstream, needs no code change. The
   Downstream contract section lists what it relies on, item by item.
2. **One connection per peer.** ChainSync, BlockFetch, KeepAlive and LeiosFetch share the main N2N connection.
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

New invariant **I-NoWait**: no code running on a start's event loop blocks on I/O of any connection.

- **One event loop per start.** A *start* is one `fetch`/`startSync` session of a helper. Its loop is chosen once.
  - Every connection of the start registers on it: the first one, reconnects and internal replacements (D3).
  - All of the start's timers and its delivery dispatcher run on it too (review r2: Fable F5).
- **Listener callbacks run on that loop, serialized** (I1, C1).
  - Today they run on the current connection's loop, which may differ after a reconnect. yaci-store makes no
    thread-identity assumption; it needs serialization and happens-before (C1).
  - One exception is kept from today, with its count and thread: `Session.start` calls `disconnected()` on each agent
    synchronously on the caller's thread, before the channel is attached. The queue is empty at that moment, so
    those notifications pass straight through (review r2: Codex F5).
- **A slow or blocked listener blocks that loop,** so reads stop, as today (C6). It can no longer deadlock the sync,
  because nothing on the loop waits for a reply.
- **Reconnect waits** (connect, retry sleep, handshake polling) never run on the start's loop. Deadlines and the stall
  watchdog keep running during an outage.
- **EB checking and mapping** (Blake2b, `withEndorserBlockTxs`, `TransactionListBuilder`) run on a small CPU pool and
  post the result back to the loop.
- **Stop.** `stop()` retires the start at once: it marks the start's generation terminal, so no new callback starts.
  - It closes the channel asynchronously, and never waits for the loop or for delivery (I-Stop).
  - A callback already running may finish, as today.
  - Start keeps today's caller-thread behaviour, but never runs on the old start's loop.

### D2. In-order delivery queue (ADR 0017 D4 semantics, without threads, a pool or blocking)

- **Queue.** Entries enter in receive order. An ordinary block is *ready*; a certifying block *waits* for its EB.
  `batchDone`, rollback, intersection, disconnect and every other callback of the start are queued **markers**, so
  their position relative to blocks is the receive order, as today.
- **One drain** delivers from the front only. It runs when an entry arrives, when a fetch completes, and on timers.
- **Fast path.** With nothing waiting, an entry is delivered at once, as today. On a network without Leios the queue
  stays empty.
- **Rules from ADR 0017 D4, kept as reviewed:**
  - announcements are indexed on read;
  - the 45 s budget starts at the front;
  - `RETRYING`, skip and halt are emitted only at the front;
  - `fetch()` is admitted after the queue drains, or from inside `batchDone`.
- **Listener exception.** A listener exception becomes that block's `onParsingError`, in its place.
  - It carries the original block bytes, which are retained through deferred delivery and charged to D5.
  - So the payload does not depend on `returnBlockCbor` or on whether the block was queued (review r2: Codex F8,
    Fable F9).
- **Commit rule.** Success, retry, skip and halt are decided by one atomic commit against the block's deadline and the
  start's cancellation, as the synchronous path does today.
  - A result that completes after the deadline loses to the timer.
  - A stop that clears the queue wins over a drain that already looked at the front.
- **Connection loss and internal replacement (review r2: Fable F1, F2; Codex F4).**
  1. Entries received but not yet started are discarded, with their fetches and their budget.
  2. The `onDisconnect` markers are delivered next, right after the last delivered entry.
  3. The start resumes from the last *delivered* block (I-Resume).

  The listener therefore sees a sequence that exists today: the one in which the connection dropped before those
  blocks were received.
- **Exactly once.**
  - Each entry leaves the queue once.
  - A resumed range repeats its last delivered block (the range start is inclusive). That block is dropped by its
    exact hash, once.
  - The duplicate check is armed only by a resume of *the same range*, never across separate `fetch` calls.
  - **Deliberate change.** Today a reconnect inside a range delivers that block and `batchStarted` a second time
    (`BlockfetchAgent`). Under D2 neither is repeated, per the owner's exactly-once requirement. It is listed as
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
- **Stalls (review r2: Codex F10; Fable F13).** The connection is replaced when any request is in flight on it
  (ChainSync headers, a BlockFetch window, LeiosFetch) and no byte has been read for 15 s.
  - The stall clock is `now − max(last byte read, end of the last listener callback)`, checked on the start's loop.
    A listener that blocks the loop therefore never counts as a stalled peer.
  - The replacement follows the D2 rule for a connection loss.
- **What a replacement looks like.** To the listener it is exactly today's reconnect: `onDisconnect`, then in
  `BlockSync` the re-intersection callbacks.
  - It is a new *trigger* of existing callbacks, listed as deviation V2.
  - `isRunning()` and the keep-alive getters behave as during today's reconnect.

### D4. `BlockRangeSync` flow control by request: header-driven BlockFetch windows

This replaces blocking the event loop and `autoRead` off, both of which deadlock on a shared socket. `fetch(from, to)`
keeps its signature and its meaning: the blocks of the chain from `from` to `to`, as the peer serves them by hash.

- **Start order.** No listener callback happens before step 3 delivers.
  1. **Validate `to`:** ChainSync `FindIntersect([to])` on the main connection.
     - **Found:** `to` is on the peer's selected chain. Learn `to`'s block number with BlockFetch `[to, to]`; that
       block is not delivered. The whole range is windowed.
     - **Not found:** `to` is on a fork the peer may still retain, and today's request would be served by hash
       (review r2: Codex F1, Fable F3).
       - Any such fork branches off within k blocks of the peer's tip (k is the security parameter: 2,160 on
         mainnet, 108 on Musashi).
       - So the range is windowed only up to the *immutable horizon* `H` = tip − k on the selected chain. The rest is
         one `RequestRange(H, to)` by hash, as today's request would be served.
       - Its repeated first block is dropped by exact hash. If that request gets `NoBlocks`, see deviation V3.
  2. **Validate `from`:** `FindIntersect([from])`. If it is not found, the range gives `noBlockFound(from, to)` and
     nothing else, as today's `NoBlocks` for a range that is not a chain.
  3. **Fetch `from`'s body** with BlockFetch `[from, from]`, as the range's first block.
     - If it is certifying, its parent's announcement is looked up first on this connection's ChainSync (D4b), which
       then re-intersects at `from`.
     - Header pipelining starts after this.
- **Windows.**
  - Pipelined ChainSync headers after `from` give each block's point and its exact `blockBodySize`. The number of
    header requests in flight never exceeds the blocks left before `to` (known from its block number), so no request
    passes `to` or parks at the tip.
  - BlockFetch asks for windows of at most W blocks and B bytes (defaults 100 and 2 MB). Byron headers give points but
    no size, so Byron windows are bounded by count only (review r2: Fable F4).
  - A window is requested only while the budget (D5) has room. An empty queue always admits one, so the front can
    always make progress.
  - **Every window is verified (I-Range):** its blocks must be exactly the expected points, in order (review r2:
    Codex F2).
- **Anomaly fallback.** These conditions all mean the peer is no longer serving the chain that was read:
  - a mismatch, an early `BatchDone` or `NoBlocks` for a window;
  - a ChainSync `RollBackward` other than the first reply after an intersection.

  Then header intake stops, and windows continue over the headers already buffered. The rest of the range is one
  `RequestRange(last received, to)`, by hash, as today's request would have been served; its repeated first block is
  dropped by exact hash.
  - A fork switch can only reach the part of the chain within k blocks of the tip, so this request covers at most
    about k blocks.
  - Its outcome passes through as today: blocks then `batchDone`, or `noBlockFound(from, to)`.
  - Residual difference (deviation V3). The peer may drop the fork's bodies before Yaci's by-hash request, in this
    fallback or in step 1's `RequestRange(H, to)`.
    - Then the blocks already delivered stay delivered, followed by `noBlockFound(from, to)` and no `batchDone`.
      Those blocks are valid blocks of the requested chain, immutable in step 1's case.
    - Today the same race gives `noBlockFound` with no block delivered.
- **Callbacks.**
  - One `batchStarted` when the first block is received, and one `batchDone` after the last block of the range.
  - `noBlockFound(from, to)` names the requested points, never internal window points.
  - **I-Suppress:** the range's internal traffic never reaches the listener: its ChainSync (roll-forwards,
    intersections, rollbacks), the `[to, to]` probe and the parent lookup. `BlockRangeSync` never emitted `onRollback`
    and still does not (review r2: Fable F3).
  - A halt stops window and header requests at once.
- **Round trips.** Header pipelining keeps BlockFetch fed.
  - A second window may be queued before the first completes, so a window boundary costs no idle round trip. Measure
    before enabling.
  - The extra header bytes and round trips, and how the Haskell node drives BlockFetch from ChainSync, are measured
    and tuned in #261.
- **D4b. Parent lookup.** Needed for a certifying block at the start of a range. ADR 0016 D4b's `FindIntersect`
  search runs on the main connection's ChainSync in step 3, before header pipelining starts, under I-Suppress.
  - Its deadline and cancellation are the start's.
  - `ParentBlockLookup`'s own connection is removed (D8).

### D5. One memory budget, counted in bytes

What Yaci holds for not-yet-delivered blocks is charged to one budget (default 32 MB, configurable). Credit is
reserved before a request is sent, moves with the data from stage to stage, and is released only when the retained
data is gone (review r2: Codex F7, Fable F8).

| Stage | Charge |
|---|---|
| Buffered headers | their bytes; at most 2,000 buffered and 100 in flight |
| BlockFetch window | the sum of its headers' `blockBodySize`, from request until each block is delivered or discarded |
| EB request | 660 kB (the `MsgLeiosBlock` limit) until the EB arrives |
| EB transactions | the exact bytes the EB references, from request through raw reply, CPU mapping and queued block until delivery |
| Written but abandoned request | held until its reply is drained or the connection closes |
| Original block bytes kept for `onParsingError` | their size, until delivery |

- **Admission.**
  - Windows and header-triggered EB prefetches are admitted only under the budget, and prefetch may use at most half
    of it.
  - The front block's own fetch is always admitted. It has priority, so a full budget can never hold back the block
    that would free it.
- **Worst case:** the budget, plus one window (2 MB), plus the front block's EB.
- **Not covered:** the by-hash `RequestRange` of D4 (step 1's tail when `to` is off the selected chain, and the
  anomaly fallback) cannot be reserved in advance.
  - It covers at most about k blocks: up to 2,160 on mainnet, 108 on Musashi.
  - It occurs only when `to` is off the peer's selected chain or a fork switch happens inside the range.
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
- **Feature off: nothing changes,** on any network. Zero downstream impact holds by construction.
- **Feature on: listener callbacks are unchanged in every era.** The request pattern does change: ChainSync headers
  plus BlockFetch windows instead of one `RequestRange`.
- **Default off for one release,** with the side-connection path kept as the fallback.
  - It becomes the default only after phase 5's exit criteria pass, mainnet throughput included. Then D8 applies.
  - yaci-store needs a Yaci 0.5.x build for Leios in any case.
- The pool's `MAX_READ_AHEAD` is untouched.

### D8. Remove the side connections

Once D1–D7 and D10 are the default, remove:
- `EndorserBlockResolver`'s side `TCPNodeClient` and its keep-alive scheduler (ADR 0017 D2);
- `PooledEndorserBlockSource`;
- `ParentBlockLookup`'s connection.

### D9. Fair egress (#253): independent

#253 is implemented as specified: per-protocol round robin, one 12,288-byte SDU per turn. It is needed for Yano's
server side, and it is not a prerequisite for this ADR.

### D10. Tip following (`BlockSync`) on the same connection, paced by delivery

D8 removes the side connection that `BlockSync` uses today. So with the feature on, `BlockSync`'s
`N2NChainSyncFetcher` connection also registers the `PipelinedLeiosFetchAgent` (D3), and its callbacks go through the
delivery queue (D2).

- **Today's ordering, corrected (review r2: Fable F6; Codex F4).** `N2NChainSyncFetcher`'s internal listener runs
  first, because listeners run last-added first. It confirms the block, setting ChainSync's resume point, and sends
  `RequestNext` *before* `BlockSync`'s listener runs.
  - Ordering holds today only because the blocked loop cannot read the next reply.
  - D10 therefore changes `N2NChainSyncFetcher`, a public class, and only behind the D7 predicate.
- **One transition: the block's delivery concluded.** It covers every way delivery can end:
  - `onBlock` returned;
  - `onBlock` threw, and its `onParsingError` was delivered;
  - a parse error was delivered and the sync continues;
  - a Byron callback was delivered;
  - a skip was delivered.

  Only then is the block confirmed as the resume point, and only then is `RequestNext` sent. A halt or a stop sends
  neither. `NoBlocks` for a tip block behaves as today.
- **Every `BlockSync` callback is queued in receive order:**
  - `rollforward` and `rollforwardByronEra`, `intersactFound`/`intersactNotFound`, `onRollback`;
  - `batchStarted`, the block callbacks, `batchDone`, `noBlockFound`, `onDisconnect`;
  - except the D1 start-up exception.

  With one block in flight, a rollback can be received only after the previous block's delivery concluded. So on an
  uninterrupted connection its position is today's.
- **Reconnect while a block waits for its EB.** The block is discarded (D2), and its resume point was never
  confirmed. ChainSync therefore re-intersects at the last delivered block, and the listener sees `onDisconnect`,
  `intersactFound`, `onRollback` to that block, then the block again. That is today's sequence for a reconnect just
  before that block arrived (I-Resume).
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
    - Duplicates are counted separately: 0 in every run.
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
| C1 | Callbacks for one listener are serialized, with happens-before between them. Its state (`syncMode`, epoch and era, the parallel-mode batch buffer) is not thread-safe. No thread identity is assumed. | D1: one pinned loop per start (I1) |
| C2 | `fetch(from, to)` delivers every block of `[from, to]`, `from` included, exactly once, in chain order. It has no gap detection, so a skipped block would be lost silently. | D2 exactly once; D4 verified windows; I2, I-Range, I-Resume |
| C3 | `batchDone` arrives after the last block of the range. From inside it, yaci-store stops the delivering `BlockRangeSync` and starts `BlockSync` from its cursor. | D2 (`batchDone` is a marker); D4 (one per range); D1 stop; I-Stop |
| C4 | `onRollback` comes after every block received before it, and no block of the abandoned fork follows it. Its cleanup deletes by slot from the persisted cursor. Every `startSync` begins with a rollback to the intersection. | D2 markers in receive order; D10 pacing and resume point; I-Rollback |
| C5 | `stop()`, `restart()` and `startSync()` are called from callbacks and from other threads, inside yaci-store's own `synchronized` methods. | D1 stop: retire at once, close asynchronously, never wait for the loop or delivery; I-Stop |
| C6 | Blocking in `onBlock` is its only backpressure: synchronous DB writes, and in parallel mode a join of up to 5 minutes every 100 blocks. | Callbacks stay on the start's loop, so a blocked listener still stops reads; windows and prefetch are admitted only under D5 |
| C7 | Health is the time since the last *block* callback (`blockReceiveDelaySeconds`, default 120 s); optional auto-recovery restarts the sync. | Unchanged 45 s front budget and skip/halt rules (D2); the 15 s stall rule now covers headers and windows too (D3) |
| C8 | A listener exception: yaci-store calls `stopSyncOnError()` and rethrows, and with `continueOnParseError` it publishes `onParsingError`'s bytes in `ErrorEvent`. | D2: `onParsingError` in place, with the original bytes; I-Stop's exception for it |
| C9 | `intersactFound`/`intersactNotFound` arrive even when no block follows. `BlockFinder` shares the `BlockSync` instance, waits up to 40 s for them, then stops it. | D10 markers; the fast path delivers at once |
| C10 | New callbacks would need no-op defaults. | No callback is added |
| C11 | `isRunning()` and the keep-alive getters are polled every 10 s. | Unchanged; an internal replacement behaves as today's reconnect (D3) |

**Deviations with the feature on.** Each is visible only in the case described, and each was reviewed against
yaci-store:
- **V1. No repeat after a reconnect inside a range.** Today such a reconnect delivers the last block and
  `batchStarted` a second time; now neither repeats (D2, exactly once). yaci-store tolerates either.
- **V2. A new trigger for an existing reconnect.** A 15 s stall of any request on the main connection replaces it.
  The listener sees today's reconnect callbacks: `onDisconnect`, and in `BlockSync` the re-intersection, whose
  `onRollback` is to the last delivered block, a no-op for yaci-store's cleanup.
- **V3. A fork's bodies dropped before Yaci's by-hash request** (D4). Already delivered blocks stay delivered, then
  `noBlockFound(from, to)`. Today it is `noBlockFound` alone.
  - yaci-store implements neither callback, so both cases end in its health restart from its cursor.
  - The delivered blocks are valid blocks of the requested chain.

**Cursors.** Three cursors are involved, and none changes meaning:
- **Yaci's delivered cursor:** the last block whose delivery concluded. It is the only resume point (I-Resume).
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

- **I1.** Listeners see the same callbacks, arguments and order as today, serialized on the start's event loop.
  The exceptions are deviations V1–V3, and D1's start-up `onDisconnect` on the caller's thread.
- **I2.** Every block of a range is delivered exactly once, also across reconnects.
- **I-NoWait.** No code on a start's event loop waits for network I/O.
- **I-Budget.** What D5 counts stays under its budget, except the front block's own fetch, one window, and D4's
  by-hash request.
- **I-Certified.** Only EBs announced by the parent of a block with `leiosCertInBody == true` are requested.
- **I-Generation.** No reply of one connection generation completes a request of another.
- **I-Range.** Every BlockFetch window delivers exactly its expected points, in order, or triggers D4's fallback; a
  range never advances past a block that was not received.
- **I-Suppress.** Internal protocol traffic never reaches the listener: the range's ChainSync, the `[to, to]` probe,
  parent lookups, and `[P, P]` fetches.
- **I-Rollback.** A rollback is delivered after every entry received before it, and no block of the abandoned fork is
  delivered after it.
- **I-Stop.** `stop()` never waits for the loop or for delivery, also when called from a callback.
  - After it returns, no new callback of that start begins. A callback already running may finish, as today.
  - Exception: the `onParsingError` that reports an exception thrown by that running callback is still delivered,
    as today.
- **I-Resume.** A start resumes only from its last *delivered* block: received-but-undelivered entries are
  discarded. In `BlockSync`, ChainSync's resume point is confirmed only when a block's delivery concludes.

## Tests

**Unit, through real `BlockfetchAgent`, `ChainsyncAgent`, `Session` and `MiniProtoClientInboundHandler` state, not
stubs:**
- **Range start and end:** `fetch(P, P)`; `to` off the selected chain, retained and pruned (V3); `from` not found.
- **Range anomalies:**
  - a missing body in the second window;
  - an early `BatchDone` in a non-final window;
  - a window with a wrong point;
  - a rollback while a window is in flight, with its late bodies and EB completions arriving after the rollback;
  - Byron headers, and a range crossing Byron → Shelley.
- **Reconnects:**
  - before the first block;
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
- an external `stop()` racing that `batchDone` on the service monitor, run under barriers (no deadlock);
- an external `stop()` while the front waits for an EB, then a restart: no stale callback afterwards;
- the real shared `BlockSync` used by a `BlockFinder`-shaped intersection check;
- a listener exception in `onBlock` with `continueOnParseError` on and off and `returnBlockCbor=false`: the payload,
  and the callbacks after the stop;
- a listener blocking for seconds: reads and window requests stop, memory stays under the budget;
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
  - the same callback sequence and the same *commit* sequence (cursor writes, per-table rows);
  - a run that ends one block past a parallel batch boundary;
  - a restart with a partial batch;
  - a recovery restart during a stalled header window;
- Yano/Haskell sync QA for D9.

## Plan

| Phase | Work | Exit criterion |
|---|---|---|
| 0 | Merge PR #256 D1 (timings) and D2 (keep-alive). Keep the D4 pool off by default, as a baseline. | Done in #256 |
| 1 | `PipelinedLeiosFetchAgent` with generations, the handshake gate and credit for abandoned requests (D3); unit tests | Agent tests; local and public LeiosFetch benchmark at 150 / 5 MB |
| 2 | Logical range (D4): start order and validation, verified windows, fallback, I-Suppress, Byron by count | Range tests; `yaci-sync-check` preview, preprod and mainnet unchanged |
| 3 | Pinned loop per start, stop, and the dispatcher (D1); delivery queue with the commit rule and the reconnect rule (D2); staged budget (D5); stall watchdog (D3) | Delivery and reconnect tests; slow-listener and fixed-heap runs; heap per budget byte |
| 4 | Header-triggered prefetch (D6); `LeiosConfig` opt-in with a system-property default (D7); parent lookups (D4b, D10); `BlockSync` on one connection (D10) | Downstream contract tests (C1–C11); full-callback recording identical except V1–V3; yaci-store run on and off |
| 5 | Default on after one release; remove the side connections (D8); update ADR 0016 and 0017 status | Full Musashi run to the tip; mainnet sync check; mainnet blocks/s from a local node within 5% of today's path |
| ‖ | #253 fair egress (D9) | #253's tests; Yano QA |
| Later | Parse ranking blocks off the event loop, in parallel, with the same ordered delivery (a general Yaci speed-up for local and LAN sync, mainnet included); its own ADR | Mainnet sync from a local node: blocks/s and CPU |

## Consequences

- **Downstream:**
  - No API change. With the feature off nothing changes. With it on, the contract yaci-store relies on is kept item by
    item, with deviations V1–V3 (Downstream contract).
  - Enabling it during the opt-in release needs a system property, or one `LeiosConfig` option, and a Yaci 0.5.x
    build of yaci-store. After phase 5, nothing.
- **Network:** one connection per peer during Leios sync, instead of one main connection plus one side connection
  (or nine with the ADR 0017 pool). Fewer TCP handshakes and less relay load.
- **Code:**
  - `BlockRangeSync` gains a header-driven windowed path, and `CompleteBlockAssembler` a non-blocking queue.
  - `N2NChainSyncFetcher` confirms and paces by delivery when the feature is on.
  - Each start pins one event loop.
  - The side-connection classes are removed in phase 5.
- **Performance:**
  - On a local node, about 15–25% faster in the POC, and about 30% with a slow listener. The node and Yaci's
    transaction model building are the limits after that.
  - On a public relay, about 5.5× today's path outside slow periods, and equal to the pool, with one connection
    instead of ten (Evidence). In a slow period it is at the mercy of one TCP flow (see Risks).

## Risks and open questions

- **One failure domain.** A stuck or invalid LeiosFetch reply now replaces the main connection. Mitigated by:
  - certified-only requests;
  - byte-based stall detection;
  - the delivered-block resume point.
- **Public relays.** A single connection is at the mercy of one TCP flow on a long path.
  - Measured: on a steady path it matches the pool (remote VM, and the public relay on 2026-11-25). In the slow
    period of 2026-11-24 it was several times slower than the pool, and today's path was slow too.
  - Haskell answers this with several *peers*, each one connection. A Yaci option to spread EB fetches over several
    relay addresses, one connection per peer, could recover it, since Musashi's name resolves to 8 relays.
  - Low priority; not part of this ADR.
- **Listener time on the event loop** still bounds a slow downstream. That is unchanged and intended (I1).
- **Design-only parts.** The POC covered `BlockRangeSync` in the Shelley-based eras, with no reconnect and no
  rollback. D10, the switch at the tip (C3), C4, C5, C9, the reconnect rule, Byron windows and the fallback paths have
  no live evidence yet. Phase 4's full-callback recording and the yaci-store run are their acceptance gates.

## Related findings (out of scope)

- **yaci-store, parallel mode.** `batchDone` does not flush a partial batch, so the switch to `BlockSync` replays its
  blocks from the committed cursor. This is pre-existing, and unchanged by this ADR.
- **A pre-existing stop race.** Today `Session.dispose` waits on `close().sync()`. An external stop that holds
  yaci-store's service monitor can wait on a loop that is itself blocked in `batchDone` on that monitor. D1's
  asynchronous close removes this race when the feature is on.
- **`BlockSync` stalls on `NoBlocks`.** On `NoBlocks` for a tip block, `BlockSync` sends no further `RequestNext`
  today. This is unchanged.
- **Open:**
  - window and budget defaults on mainnet;
  - whether to queue a second BlockFetch window;
  - CIP-0164's multi-EB request, once relays support it.

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
