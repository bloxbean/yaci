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
  - Caps: count (default 150) and expected reply bytes (default 5 MB), both configurable.
  - One request is always allowed, so an oversized reply still makes progress.
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

## Evidence (POC, 2026-11-24)

The POC is a local branch, `poc/leios-one-connection-pipelined`, on top of PR #256; it is not pushed. Its diff, the
`LeiosFetchBench` source and every run's summary and callback trace are kept with the author and can be attached to
#260 on request.

- **Local node:** the Musashi `rc-2026w45` Haskell relay at `127.0.0.1:3101`.
- **Public relay:** `52.29.179.71:3001`, about 250 ms TCP connect.

### LeiosFetch alone (64 certified EBs, 18.7 MB, from block 9,200; identical bytes in every run)

| Peer | Config | EBs/s | MB/s |
|---|---|---|---|
| local | 1 connection, depth 1 | 81–105 | 24–31 |
| local | 1 connection, depth 16 | 73–104 | 21–30 |
| public | 1 connection, depth 1 (today's pattern) | 0.80 | 0.23 |
| public | 1 connection, depth 5 | 7.0 | 2.05 |
| public | **1 connection, depth 16, 1.2 MB cap** | **10.2–10.5** | **3.0–3.1** |
| public | 8 connections, depth 1 | 4.5–12.1 | 1.3–3.6 |

- **Public runs vary a lot between runs.** Some collapse to about 0.5 EBs/s with 8–30 s waits, at any depth or
  connection count.
- **Locally they never do.** So the stalls lie on the public relay or the WAN path, not in Yaci or in pipelining.

### Full sync with `BlockRangeSync` (listener callback trace checked)

| Run | Range | Time |
|---|---|---|
| Local, today's path | genesis → 20,000 | 52–65 s |
| Local, one connection (D1–D6) | genesis → 20,000 | 42–55 s |
| Local, 9-connection pool | genesis → 20,000 | 46–49 s |
| Local, slow listener (2 ms/block): today / one connection | genesis → 20,000 | 88 s / 67 s |
| Local, one connection, 512 MB heap: normal / slow listener | genesis → 20,000 | 55 s / 68 s |
| Public, today's path | genesis → 11,000 | 14:05, 10:29 |
| Public, one connection | genesis → 11,000 | 14:30, 9:33; 4:31 without header prefetch |
| Public, 9-connection pool | genesis → 11,000 | 2:38 |

- **Correctness:**
  - All 25 runs passed: no parsing errors, gaps, retries or missing EBs.
  - Every run's listener callback trace up to the stop block is byte-identical to today's path: 19 local runs at
    20,000 (`7c018e3c…`), 6 public runs at 11,000 (`3f1e0608…`).
- **Local:** Yaci on one connection keeps up with what the local node serves, about 400 blocks/s and 22 MB/s on
  Musashi, while that node is itself still syncing. A profile shows the remaining CPU goes to building the
  transaction model, not to the network: EB transactions on the CPU pool, ranking blocks on the event loop. Pool size
  (4/8/12 threads) made no difference.
- **Public:** the relay's delivery on one connection swung between 0.1 and 4 MB/s within single runs.
  - In fast periods one pipelined connection matches the pool: 3.07 vs 3.55 MB/s, measured back to back.
  - In slow periods the pool wins, because each TCP connection gets its own share of a congested or lossy path. We
    could not confirm loss directly: the host blocks ICMP, and macOS hides the TCP counters.

## Invariants

- **I1.** Listeners see the same callbacks, blocks, transactions, order and thread as today (checked by the trace
  digest).
- **I2.** Every block of a range is delivered exactly once, also across reconnects.
- **I-NoWait.** No event loop waits for network I/O.
- **I-Budget.** What D5 counts stays under its budget, except the front block's own fetch.
- **I-Certified.** Only EBs announced by the parent of a block with `leiosCertInBody == true` are requested.
- **I-Generation.** No reply of one connection generation completes a request of another.

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

**Live:**
- the Musashi trace digest against today's path, locally and on the public relay;
- a full run to the tip;
- `yaci-sync-check` on preview, preprod and mainnet with the feature on (the Byron path and no regression);
- a slow-listener run with a fixed heap;
- Yano/Haskell sync QA for D9.

## Plan

| Phase | Work | Exit criterion |
|---|---|---|
| 0 | Merge PR #256 D1 (timings) and D2 (keep-alive). Keep the D4 pool off by default, as a baseline. | Done in #256 |
| 1 | `PipelinedLeiosFetchAgent` with generations and the handshake gate (D3); unit tests | Agent tests; local and public LeiosFetch benchmark |
| 2 | Logical range with header-driven windows (D4), including the edge cases above; Byron; reconnect | Range tests; `yaci-sync-check` preview, preprod and mainnet unchanged |
| 3 | Serialized dispatcher and non-blocking delivery queue with the commit rule (D1, D2); one byte budget (D5) | Delivery tests; slow-listener and 512 MB heap runs |
| 4 | Header-triggered prefetch (D6); `LeiosConfig` opt-in (D7); parent lookup on ChainSync | Musashi trace digest; local and public timings |
| 5 | Default on after one release; remove the side connections (D8); update ADR 0016 and 0017 status | Full Musashi run to the tip; mainnet sync check |
| ‖ | #253 fair egress (D9) | #253's tests; Yano QA |
| Later | Parse ranking blocks off the event loop, in parallel, with the same ordered delivery (a general Yaci speed-up for local and LAN sync, mainnet included); its own ADR | Mainnet sync from a local node: blocks/s and CPU |

## Consequences

- **Downstream:** no API change. yaci-store sets one `LeiosConfig` option during the opt-in release, then nothing.
- **Network:** one connection per peer during Leios sync, instead of one main connection plus one side connection
  (or nine with the ADR 0017 pool). Fewer TCP handshakes and less relay load.
- **Code:** `BlockRangeSync` gains a header-driven windowed path, and `CompleteBlockAssembler` a non-blocking queue.
  The side-connection classes are removed in phase 5.
- **Performance:**
  - On a local node, about 15–25% faster in the POC, and about 30% with a slow listener. The node and Yaci's
    transaction model building are the limits after that.
  - On a public relay, it is at the mercy of one TCP flow (see Risks).

## Risks and open questions

- **One failure domain.** A stuck or invalid LeiosFetch reply now replaces the main connection. Mitigated by:
  - certified-only requests;
  - byte-based stall detection;
  - the delivered-block resume point.
- **Public relays.** A single connection is at the mercy of one TCP flow on a long path. Measured: in slow periods it
  is several times slower than a pool.
  - Haskell answers this with several *peers*, each one connection.
  - A Yaci option to spread EB fetches over several relay addresses, one connection per peer, could recover it, since
    Musashi's name resolves to 8 relays.
  - This needs a decision; it is not part of this ADR.
- **Listener time on the event loop** still bounds a slow downstream. That is unchanged and intended (I1).
- **Open:**
  - window and budget defaults on mainnet;
  - whether to queue a second BlockFetch window;
  - the policy when `to` is unreachable on the peer's chain;
  - CIP-0164's multi-EB request, once relays support it.

## Revision history

- **r1** (2026-11-25): initial proposal, with the POC evidence and the findings of two independent reviews of the
  POC folded into D1–D8 and Tests.
