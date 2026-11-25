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

1. **Downstream contract unchanged.** `BlockChainDataListener` receives the same callbacks with the same blocks and
   transactions, in the same order and positions, on the same thread:
   - `onBlock` with complete blocks;
   - one `batchStarted` and one `batchDone` per `fetch(from, to)`;
   - `onParsingError`, `onEndorserBlockUnavailable`, `onDisconnect`;
   - halt and skip.

   Every block is delivered exactly once. yaci-store, the main downstream, needs no change.
2. **One connection per peer.** ChainSync, BlockFetch, KeepAlive and LeiosFetch share the main N2N connection.
3. **Certified EBs only.** A node disconnects a request for an EB it does not hold (ADR 0013a).
4. **Bounded memory, with backpressure.** The main deployment is yaci-store syncing from a local or LAN node, where
   Yaci's CPU and memory, not the network, are the limit.
5. **Few round trips.** Public relays are shared with other Yaci users.
6. **Nothing changes before Dijkstra.** Preview, preprod and mainnet behave as today.

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

### D1. Nothing on an event loop waits for the network

New invariant **I-NoWait**: no code running on a connection's event loop blocks on I/O of any connection.

- **Listener callbacks still run on the main connection's event loop** (I1).
  - A slow listener slows the sync, as today.
  - It can no longer deadlock it, because no thread waits for a reply on that loop.
- **EB checking and mapping** (Blake2b, `withEndorserBlockTxs`, `TransactionListBuilder`) run on a small CPU pool, and
  post the result back to the loop.
- **One serialized, generation-aware dispatcher per start** runs every delivery: blocks, markers, retries and the
  terminal outcome. It stays executable while the connection is down (review: Codex 3–4, Fable 7).
  - So a deadline still fires during an outage.
  - Two callbacks never overlap across a reconnect.
  - Reconnect waits must not run on that executor.

### D2. In-order delivery queue (ADR 0017 D4 semantics, without threads, a pool or blocking)

- **Queue.** Blocks enter in chain order. An ordinary block is *ready*; a certifying block *waits* for its EB.
- **One drain** delivers from the front only. It runs when a block arrives, when a fetch completes, and on timers.
- **Fast path.** With nothing waiting, a block is delivered at once, exactly as today, so before Dijkstra the queue
  stays empty.
- **Rules from ADR 0017 D4, kept as reviewed:**
  - announcements are indexed on read;
  - `batchDone`, rollback and disconnect are queued **markers**;
  - a listener exception becomes that block's `onParsingError`, in its place;
  - the 45 s budget starts at the front;
  - `RETRYING`, skip and halt are emitted only at the front;
  - `fetch()` is admitted after the queue drains, or from inside `batchDone`.
- **Commit rule (review: Codex 8, Fable P3).** Success, retry, skip and halt are decided by one atomic commit against
  the block's deadline and the start's cancellation, as the synchronous path does today.
  - A result that completes after the deadline loses to the timer.
  - A stop that clears the queue wins over a drain that already looked at the front.
- **Exactly once.**
  - Each queued entry leaves the queue once.
  - A resumed range repeats its first block (the range start is inclusive), and that duplicate is dropped once.
  - The duplicate check is armed only by a resume of *the same range*, never across separate `fetch` calls
    (review: Codex 7, Fable 4).

### D3. LeiosFetch on the main connection, pipelined like the Haskell client

- **Agent.** The main connection registers a `PipelinedLeiosFetchAgent`.
  - It sends nothing until a certifying block needs it, so peers that do not run LeiosFetch never see protocol 19.
  - It is used only after the *current* connection's handshake has been accepted with V15 or later, not app-layer
    (ADR 0016 D4a's rule), and not before that handshake completes (review: Fable 5).
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
- **Generations (review: Codex 5, Fable 6).** The FIFO, writes, readiness and abort belong to one connection
  generation.
  - Abort marks the generation terminal *before* failing its futures, and only that generation's event loop writes.
  - A request written cannot be cancelled. Its reply is drained and discarded, or the connection is replaced.
- **Stalls.** Requests in flight with nothing received from the peer for 15 s replace the main connection.
  - This is measured on the event loop from bytes *read*, so a slow listener is never taken for a stalled peer
    (review: Fable 7).
  - The resume point is the last delivered block, and only the FIFO needs replacing; one connection is the price
    of this.
  - Fetches fail and retry within each block's budget.
  - An EB that preceded such a replacement is not prefetched again; only its own block retries it (review: Fable 8).

### D4. `BlockRangeSync` flow control by request: header-driven BlockFetch windows

This replaces blocking the event loop and `autoRead` off, both of which deadlock on a shared socket.

- **`fetch(from, to)` keeps its signature and meaning.** Internally:
  1. Pipelined **ChainSync headers** from `from` give the points, and each block's exact `blockBodySize`.
  2. BlockFetch asks for **bounded windows**: at most W blocks and B bytes (defaults 100 and 2 MB).
  3. A window is requested only while the delivery budget (D5) has room.
  4. An empty queue always admits a window, so the front can always make progress.
- **Range semantics (review: Codex 1–2, 6; Fable 1–3, 10, 12).** A *logical range* object owns `from`, `to`, the
  delivered cursor, and a "last block received, waiting for `BatchDone`" state. They survive reconnects and are never
  inferred from BlockFetch's own cursor.
  - **A single-block range, or a resume at `to`,** is one final window `[to, to]`. No header past `to` is needed.
  - **The end.**
    - The range ends at `to` by hash.
    - A header past `to.slot` without a match ends it with `noBlockFound(from, to)`: the requested points, never
      internal window points.
    - `IntersectNotFound` also gives `noBlockFound(from, to)`.
  - **Headers.** Requests stop at `to.slot`, and are bounded by the peer's tip. Header replies are tagged by range
    generation. A new range waits for the old pipeline to drain, or replaces the connection when the old pipeline is
    parked at the tip.
  - **Rollback inside a range** invalidates the buffered headers after the rollback point, and re-intersects.
  - **The next window** is issued from `readyForNextBatch`, after `BlockfetchAgent` has reset its cursor.
  - **Exactly one `batchStarted` and one `batchDone` per range,** reset on every terminal path, `NoBlocks` included.
  - **Byron headers** produce points too. Otherwise a range that touches Byron uses today's single-request path.
  - **A halt** stops window requests at once.
- **Round trips.**
  - Header pipelining keeps BlockFetch fed.
  - A second window may be queued before the first completes (BlockFetch request pipelining), so a window boundary
    costs no idle round trip. Measure before enabling.
  - Each header crosses the connection a second time (ChainSync, then BlockFetch's body). The bytes and round trips
    this costs, and how the Haskell node drives BlockFetch from ChainSync, are measured and tuned in #261.
- **Parent of a range-start certifying block** (ADR 0016 D4b): the same `FindIntersect` search, on the main
  connection's ChainSync before the range starts. Its deadline and cancellation are the start's. The lookup's own
  connection is removed (review: Codex 11).

### D5. One memory budget, counted in bytes

What Yaci holds for not-yet-delivered blocks is charged to one budget (default 32 MB, configurable). The charge:
- **covers** queued block bodies, EB transactions reserved, in flight, completed or mapped but not yet delivered, and
  LeiosFetch replies in flight;
- **is reserved before a request is sent,** and released when the block is delivered or the fetch is dropped
  (review: Codex 9, Fable 9).

Admission:
- BlockFetch windows and header-triggered EB prefetches are admitted only under the budget.
- Prefetch may use at most half of it.
- The front block's own fetch is always admitted. It has priority, so a full budget can never hold back the block
  that would free it.

Measured in the POC: Musashi from genesis to block 20,000 completes with a 512 MB heap, also with a listener slowed to
2 ms per block.

### D6. EB fetch starts from the header

- **When.** A certifying header (`leiosCertInBody`, ChainSync) whose parent's announcement is known starts the EB
  fetch before BlockFetch delivers the body. This is certified-only, as Haskell's roll-forward offer is.
- **Limit.** Bounded by D5, and by a maximum number of prefetched EBs (default 64).
- **Configurable,** default on for remote peers. Against a local node it measured no gain, because round trips there
  are under a millisecond.

### D7. Opt-in, then default

- **One predicate** enables D1–D6 for both the client and the source (review: Codex 10). It is a `LeiosConfig` setting,
  not a JVM property.
- **Default off for one release,** with the side-connection path kept as the fallback. Then it becomes the default,
  and D8 applies.
- The pool's `MAX_READ_AHEAD` is untouched (review: Fable 15).

### D8. Remove the side connections

Once D1–D7 are the default, remove:
- `EndorserBlockResolver`'s side `TCPNodeClient` and its keep-alive scheduler (ADR 0017 D2);
- `PooledEndorserBlockSource`;
- `ParentBlockLookup`'s connection.

### D9. Fair egress (#253): independent

#253 is implemented as specified: per-protocol round robin, one 12,288-byte SDU per turn. It is needed for Yano's
server side, and it is not a prerequisite for this ADR.

### D10. Tip following (`BlockSync`) on the same connection, paced by delivery

D8 removes the side connection that `BlockSync` uses today, so `BlockSync` gets the same treatment, on its own
connection:
- **Agent.** Its `N2NChainSyncFetcher` connection registers the `PipelinedLeiosFetchAgent` (D3).
- **Queue.** Its blocks go through the same delivery queue (D2), with `onRollback`, `intersactFound` and
  `intersactNotFound` as queued markers.
- **Pacing.** `BlockSync` asks ChainSync for the next header only after the previous block has been *delivered*, not
  just received, as today's synchronous path does. A certifying block waiting for its EB therefore holds back the next
  `RequestNext` without blocking any thread.
  - So a rollback can only be received after every earlier block was delivered. Its position in the callback stream
    is the same as today by construction.
  - The queue holds at most one block, so `BlockSync` needs no window or budget.
- **At the tip** the peer only serves a certifying header after adopting it, which needs the EB's transactions, so the
  EB is available on the same connection.

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

### Full sync with `BlockRangeSync` (listener callback trace checked)

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
  - Every run's listener callback trace up to the stop block is byte-identical to today's path: 19 local runs at
    20,000 (`7c018e3c…`), 18 public and remote-VM runs at 11,000 (`3f1e0608…`).
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

What a downstream relies on was checked against yaci-store, the main downstream (commit `1ff6f5ed`; it pins Yaci
0.4.x today and uses no Leios callback). Each item is kept by this design, as listed. No downstream code changes;
the only new setting is the D7 opt-in.

| # | yaci-store relies on | Kept by |
|---|---|---|
| C1 | Callbacks for one listener are serialized, with happens-before between them. Its state (`syncMode`, epoch and era, the parallel-mode batch buffer) is not thread-safe. | D1: one serialized dispatcher; callbacks on the connection's event loop (I1) |
| C2 | `fetch(from, to)` delivers every block of `[from, to]`, `from` included, exactly once, in chain order. It has no gap detection: a skipped block would be lost silently. | D2 exactly once; D4 logical range; I2, I-Resume |
| C3 | `batchDone` arrives after the last block of the range. From inside it, yaci-store stops the `BlockRangeSync` that is delivering, and starts `BlockSync` from its cursor. | D2 (`batchDone` is a marker); D4 (one `batchDone` per range); I-Stop |
| C4 | `onRollback` comes after every block received before it, and no block of the abandoned fork follows it. Its cleanup deletes by slot from the persisted cursor, and every `startSync` begins with a rollback to the intersection. | D2 markers; D10 pacing; I-Rollback |
| C5 | `stop()`, `restart()` and `startSync()` are called from callbacks and from other threads. They must not wait for delivery, and once they return the old session delivers nothing. | D1 generations; I-Stop |
| C6 | Blocking in `onBlock` is its only backpressure (synchronous DB writes; a 5-minute join every 100 blocks in parallel mode). | Callbacks stay on the event loop, so a blocked listener still stops reads and drains; windows (D4) and prefetch (D6) are admitted only under the D5 byte budget. Nothing buffers a whole range. |
| C7 | A callback gap over 120 s triggers yaci-store's auto-restart. | The 45 s front budget, retry and skip/halt rules are unchanged (D2) |
| C8 | A listener exception is reported as `onParsingError` in its place, and delivery continues if yaci-store chooses to. | D2 |
| C9 | `intersactFound` and `intersactNotFound` arrive even when no block follows (`BlockFinder` shares the `BlockSync` instance). | D10 markers; the fast path delivers at once |
| C10 | New callbacks have no-op defaults. | No callback is added |

The cursor needs no change. A block is either delivered, and then yaci-store persists it before `onBlock`
returns, or it is not yet delivered, and yaci-store's cursor is still behind it. Blocks queued but not delivered when
the process stops are fetched again on restart, from yaci-store's own cursor, as blocks in flight are today.

## Invariants

- **I1.** Listeners see the same callbacks, blocks, transactions, order and thread as today (checked by the trace
  digest).
- **I2.** Every block of a range is delivered exactly once, also across reconnects.
- **I-NoWait.** No event loop waits for network I/O.
- **I-Budget.** What D5 counts stays under its budget, except the front block's own fetch.
- **I-Certified.** Only EBs announced by the parent of a block with `leiosCertInBody == true` are requested.
- **I-Generation.** No reply of one connection generation completes a request of another.
- **I-Rollback.** A rollback is delivered after every block received before it, and no block of the abandoned fork
  is delivered after it (C4).
- **I-Stop.** `stop()` never waits for delivery, also when called from a callback. After it returns, no callback of
  that session is delivered: queued entries are discarded by generation (C3, C5).
- **I-Resume.** After a reconnect, a range resumes from the last *delivered* block, never from what was only
  received, so nothing is skipped (C2).

## Tests

**Unit, through real `BlockfetchAgent`, `Session` and `MiniProtoClientInboundHandler` state, not stubs:**
- `fetch(P, P)`;
- `to` absent, `IntersectNotFound`, a rollback inside a range, Byron headers;
- a chained `fetch` from `batchDone` mid-chain, and at the tip with a parked `AwaitReply`;
- a reconnect before the first block, after the last block before `BatchDone`, while idle, then an overlapping range;
- an abort followed by a submit and a late reply; a write before the handshake;
- the stall watchdog under a listener blocking for more than 15 s;
- a front deadline during a long outage;
- a late success against the deadline; stop racing a ready front;
- retained EB bytes with a slow front; `NoBlocks` mid-range;
- ordering, halt and skip, listener exceptions and `fetch()` in `batchDone`: the ADR 0017 D4 list.

**Downstream contract (C1–C10), with a listener shaped like yaci-store's `BlockFetchService`:**
- `stop()` of the delivering `BlockRangeSync` and `BlockSync.startSync()` from inside `batchDone`;
- `stop()` from another thread while the front waits for an EB, then a restart: no stale callback afterwards;
- a rollback received while a certifying block waits (`BlockSync`), and across a queued certifying block;
- `intersactFound` with no following block;
- a listener blocking for seconds: reads and window requests stop, memory stays under the budget;
- a listener exception in `onBlock` with `continueOnParseError` on and off.

**Live:**
- the Musashi trace digest against today's path, locally and on the public relay;
- a full run to the tip;
- `yaci-sync-check` on preview, preprod and mainnet with the feature on (the Byron path and no regression);
- a slow-listener run with a fixed heap;
- Yano/Haskell sync QA for D9;
- yaci-store on Musashi with the feature on, against a run with it off: same cursor, same row counts per table,
  including a restart mid-range and the switch to `BlockSync` at the tip.

## Plan

| Phase | Work | Exit criterion |
|---|---|---|
| 0 | Merge PR #256 D1 (timings) and D2 (keep-alive). Keep the D4 pool off by default, as a baseline. | Done in #256 |
| 1 | `PipelinedLeiosFetchAgent` with generations and the handshake gate (D3); unit tests | Agent tests; local and public LeiosFetch benchmark |
| 2 | Logical range with header-driven windows (D4), including the edge cases above; Byron; reconnect | Range tests; `yaci-sync-check` preview, preprod and mainnet unchanged |
| 3 | Serialized dispatcher and non-blocking delivery queue with the commit rule (D1, D2); one byte budget (D5) | Delivery tests; slow-listener and 512 MB heap runs |
| 4 | Header-triggered prefetch (D6); `LeiosConfig` opt-in (D7); parent lookup on ChainSync; `BlockSync` on one connection (D10) | Musashi trace digest; local and public timings; downstream contract tests (C1–C10); yaci-store run on and off |
| 5 | Default on after one release; remove the side connections (D8); update ADR 0016 and 0017 status | Full Musashi run to the tip; mainnet sync check |
| ‖ | #253 fair egress (D9) | #253's tests; Yano QA |
| Later | Parse ranking blocks off the event loop, in parallel, with the same ordered delivery (a general Yaci speed-up for local and LAN sync, mainnet included); its own ADR | Mainnet sync from a local node: blocks/s and CPU |

## Consequences

- **Downstream:** no API change, and the contract yaci-store relies on is kept item by item (Downstream contract).
  yaci-store sets one `LeiosConfig` option during the opt-in release, then nothing.
- **Network:** one connection per peer during Leios sync, instead of one main connection plus one side connection
  (or nine with the ADR 0017 pool). Fewer TCP handshakes and less relay load.
- **Code:** `BlockRangeSync` gains a header-driven windowed path, and `CompleteBlockAssembler` a non-blocking queue.
  The side-connection classes are removed in phase 5.
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
- **Open:**
  - window and budget defaults on mainnet;
  - whether to queue a second BlockFetch window;
  - the policy when `to` is unreachable on the peer's chain;
  - CIP-0164's multi-EB request, once relays support it.

## Revision history

- **r1** (2026-11-25): initial proposal, with the POC evidence and the findings of two independent reviews of the
  POC folded into D1–D8 and Tests.
- **r2** (2026-11-25): D3 defaults stated as Haskell's 150 requests / 5 MB, with the measurement, and the depth
  reached is logged; new D10 (tip following in `BlockSync` on one connection, paced by delivery); new Downstream
  contract section (C1–C10, checked against yaci-store) with I-Rollback, I-Stop, I-Resume and their tests; Evidence
  adds the remote-VM runs, a second public series and the 150/5 MB benchmark (37 runs, traces identical); Risks and
  Consequences updated: on a steady path one connection matches the pool.
