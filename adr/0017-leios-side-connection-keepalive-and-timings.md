# ADR 0017: Leios sync speed: measure the endorser block fetch, keep one side connection, deliver in order while fetching ahead

- Status, per decision:
  - **D1, D2: Proposed, built and measured.** Risk low.
    - Only a Dijkstra block that certifies an endorser block (EB) uses the side connection. Preview, preprod and
      mainnet never open one.
    - What reaches listeners, and when, does not change.
  - **D4: being evaluated, design only, not built.** Risk medium.
    - D4 changes when blocks are read. It is decided only after a prototype passes the D4 evaluation.
- Date: 2026-11-21
- Amends ADR 0016:
  - D4a, reuse of the side connection, by D2;
  - D3b, delivery of a certifying block before the next block is read, by D4, once D4 is decided.

  Everything else in ADR 0016 stands.
- Issue: #255, and the review discussion there. Review of this ADR: #256.

## Context

To complete a certifying block, Yaci fetches the EB that the block certifies, and then the EB's transactions, over a
side connection to the main connection's relay (ADR 0016 D4a). That connection carried only the handshake and
LeiosFetch. It sent no keep-alive, and a relay drops a connection that sends no keep-alive for about 97 s. ADR 0016
handled this by never keeping a side connection long enough to reach that limit: before a fetch, it replaced one that
was older than 45 s, or that had been idle for 30 s.

A full Musashi sync check (#252) synced at about 150 blocks/s until certifying blocks start, then at 2–6 blocks/s,
and logged about 150 side connections in 175 minutes. #255 proposed reusing the connection and fetching EBs ahead of
time. The review on #255 showed that fetching ahead on an announcement asks for EBs that may never be certified. Nodes
disconnect such requests from w41 on (ADR 0013a). In range sync it would also gain only milliseconds. That part is
withdrawn.

### Measurement (Stage 0)

This ADR's branch adds a debug line for each certifying block, with the time spent in each phase, and a debug line
each time a side connection opens or is replaced, with the reason. `next` (`f1d50e37`) plus those log lines synced
Musashi from genesis on 2026-11-21, against `leios-node.play.dev.cardano.org:3001`, in one range, for 15 minutes:
`run.sh <checkout> <out> musashi -- -Dsync.segments=1
-Dorg.slf4j.simpleLogger.log.com.bloxbean.cardano.yaci.helper.EndorserBlockResolver=debug`.

| Measured over blocks 9,092–14,180 | Value |
|---|---|
| Blocks that certify an EB | 1,458 of 5,089 (29%) |
| Share of the wall time spent inside successful EB fetches | 89% (792 s of 889 s) |
| Time per certifying block, median | 503 ms: EB request 242 ms, transactions 245 ms, mapping 16 ms |
| Throughput | 5.7 blocks/s, 1.64 EBs/s, 1,619 transactions/s, 0.41 MB/s |
| Transaction batches per EB | always 1 (about 1,035 transactions, 257 KB; the batch limit is 500 KB) |
| Side connections opened | 25: 13 replaced for being older than 45 s, 2 after the relay address changed |
| Fetches that waited for a new side connection | 23 of 1,458, about 477 ms each: 10.7 s, 1.2% of the wall time |
| Round trip to the relay's 8 addresses (TCP connect) | 170–280 ms |

**What the 89% is.** It is the elapsed time of *successful* resolver calls.
- **Included:** each EB phase and transactions phase covers transfer, decoding and verification, not only waiting.
- **Not included:** failed attempts, retry pauses, `TransactionListBuilder.build` and the listener.
- **Measuring D4:** once fetches overlap, the per-call durations can add up to more than the wall time. So D4 is
  measured end to end, on a fixed block range and relay address, with the listener's time reported separately.

What the numbers show:
- **Round trips are the plausible bottleneck.** Each certifying block costs two requests, one after the other (the
  EB, then its transactions), at a measured 170–280 ms round trip, while the main connection waits (ADR 0016 D3b).
- **Replacing the side connection costs about 1%.** Reusing it is worth doing for the relay's sake and for fewer
  handshakes, but it is not what makes Musashi slow.
- **In this range every EB fits one transaction batch.** Sending batches without waiting for each reply would gain
  nothing here (D3).

## Decisions

### D1. The fetch is measured (Stage 0)

- For each certifying block, `EndorserBlockResolver` logs at debug:
  - the block number, the EB hash, and the number of transactions, batches and bytes;
  - the time in all, and per phase: parent lookup, a ready side connection (and whether it was new), the EB request,
    the transactions, and mapping.
- It logs at debug, with a number per resolver, when a side connection opens and when one is replaced, with the reason.
- This is debug logging only. Nothing is added to the public API.

### D2. One side connection per start, kept alive (amends ADR 0016 D4a)

- **Keep-alive.**
  - The side connection carries a `KeepAliveAgent` as well.
  - After its handshake is accepted, it sends `MsgKeepAlive` every 20 s (`KEEP_ALIVE_MILLIS`), as Yaci's sync
    connections are kept alive.
  - At most one keep-alive is in flight. The next one is sent only after `MsgKeepAliveResponse`.
- **A keep-alive unanswered for two intervals (40 s) closes the connection.** That is well under the relay's limit.
  The next fetch then opens a new connection.
- **No replacement for age.** The 45 s rule is removed.
- **An idle connection closes itself after 5 minutes** (`MAX_IDLE_MILLIS`, raised from 30 s). For example, a
  `BlockRangeSync` left open after `batchDone` does not keep a side connection forever. A connection is marked in use
  when a fetch takes it, so the idle rule never closes one under an attempt.
- **Unchanged from ADR 0016 D4a.** The connection is still replaced when it closes, when an attempt on it fails, or
  when the main connection's address or network magic changes.
- **The timer.**
  - One shared daemon scheduler (`yaci-leios-keepalive`) runs every side connection's timer.
  - Each tick, and the connection's fail and close, run under the connection's own lifecycle lock. So a tick either
    finishes before a close or sees the connection closed. A closed connection sends nothing, and its timer is
    cancelled.
  - A tick that throws closes the connection. Otherwise the scheduler would drop the timer silently, ending both the
    keep-alive and the idle close.
- **Detaching.** The resolver detaches a side connection by compare-and-set. So a late close of an old connection, from
  its timer or a failed attempt, can never detach the connection that replaced it.

### D3. Not in this ADR

- **Fetching on the parent's announcement:** withdrawn. Yaci only asks for certified EBs (ADR 0013a).
- **Several transaction requests in flight for one EB:** not needed while EBs fit one batch, as in the measured range.
  Larger EBs later in the chain may need several batches (not measured). If so, they are covered by the requests in
  flight of D4.

### D4. Deliver in order while fetching ahead (being evaluated)

This is the only change that addresses the measured round trips. The requirement on it (I5): a listener receives the
**same sequence of callbacks** as today:
- `onBlock` with complete blocks, in chain order, on the same thread;
- `batchDone`, `onParsingError`, `onEndorserBlockUnavailable` and the halt, in the same positions.

**Scope: `BlockRangeSync` only**, where initial sync spends its time.
- **`BlockSync` keeps today's synchronous path.** Near the tip it fetches one block about every 20 s and needs no
  queue. Its rollbacks, intersections and `confirmBlock` are therefore untouched.
- **`BlockSync` may start a certifying block's EB fetch from its header,** which carries `leiosCertInBody` one round
  trip before the body arrives. That is still certified-only.

**Today.** A certifying block stops the main connection until its EB and transactions have arrived (ADR 0016 D3b).
So Yaci only ever knows of one EB to fetch.

**With D4:**
- **Queue.** Every block BlockFetch delivers goes into a bounded queue in chain order, with its raw bytes kept:
  - an ordinary block is *ready* at once;
  - a certifying block is *waiting*, and its EB fetch starts at once (certified only).
- **Announcements are indexed when a block is read, not when it is delivered.** Otherwise a child queued behind its
  parent would not find the parent's announcement (today `CompleteBlockAssembler` caches it at delivery).
- **One ordered drain.** Fetches publish their results to a single drain on the main connection's event loop. The drain
  delivers from the front of the queue only, and only when the front is ready. Every listener call happens in the drain.
- **Errors.** The drain wraps each listener call as `BlockfetchAgent.onReceiveBlocks` does today: an exception
  becomes `onParsingError`, with that block's raw bytes, at that block's position, and the drain continues as the
  agent does today.
- **Markers.** The wire `BatchDone` becomes a marker queued after the last block of the range. Listener `batchDone`
  runs only once every block before it has been delivered. A listener may call `fetch()` from inside `batchDone`:
  the next range starts then, and the old range's cleanup does not touch the new range's cursor.
- **Budget.**
  - A certifying block's 45 s budget (ADR 0016 D3b) starts when it reaches the front of the queue, not when it is
    queued. A fetch that started earlier and is still running continues inside that budget.
  - A failure while the block is behind the front is internal: no callback, and the fetch is retried.
  - `RETRYING`, the skip event with its block, and the strict halt are emitted only at the front, on the event loop,
    in order.
  - **Strict halt** at block A: every block before A is delivered, then `SYNC_HALTED`. No callback for any block after
    A, and no `batchDone`. All queued fetches are cancelled.
- **Several fetches at once,** `N` at a time (for example 4–8), oldest first, the front block always first.
  - **First choice:** one side connection with a bounded FIFO of requests in flight, as `leiosFetchClientPeerPipelined`
    in ouroboros-consensus `840e789b` (the w42 pin) does. Each request carries its own expected point, reply type and
    transaction bitmap, and replies are matched in FIFO order.
  - **Cancelling a written request** (halt or stop) still drains its reply in order, or the side connection is
    retired. The next block must never receive another block's reply.
  - **A failure on the side connection settles every request in flight.** Each one is retried oldest first on a new
    connection, within its block's budget.
  - **A small pool of connections** is compared only if measurements of the single connection justify it, under the
    same total limits on requests and bytes.
- **Memory and backpressure.**
  - **What the caps count:** queued raw blocks, completed EB transactions (including those completed out of order
    behind a slow front), replies in flight, and the reconnect cache.
  - **Small caps.** About `2 × N` certifying blocks of lead keeps `N` fetches busy. A cap of a few dozen blocks and a
    byte cap sized from the largest measured EB is enough, and keeps any pause short.
  - **Pausing.** When the queue is full, reading from the main socket pauses (`autoRead` off) until it drains below a
    low mark.
    - That pause stops *every* mini-protocol on the socket, including KeepAlive replies and frames already decoded
      in the current read. So the caps must keep a pause well under the relay's keep-alive limit (~97 s) and any
      application watchdog on `getLastKeepAliveResponseTime()`.
    - Messages already decoded when the pause starts are still queued.
  - **A single block larger than the byte cap** is admitted when the queue is empty, so it always makes progress.
- **Reconnect.**
  - Completions from the old channel are invalidated, by a generation number per connection.
  - The resume point is the last *delivered* block. `BlockfetchAgent` today advances its `from` cursor when a block is
    received, so D4 adds a delivery confirmation that moves the resume point instead.
  - The range start is inclusive, so the first callback after a reconnect is the block after the last delivered one.
  - The range end and the delivered cursor are kept until the listener has finished.
  - Queued blocks not yet delivered are fetched again. Completed EBs stay in a small cache by EB hash, counted in the
    byte cap, so they are not fetched twice.
- **`fetch()` admission.** A new range is accepted only once the queue has drained, apart from from inside
  `batchDone`. That matches how `checkFetch` refuses one today while a certifying block waits.
- **Unchanged before Leios.** With no certifying block, the queue stays empty and every block takes the path it takes
  today. Preview, preprod and mainnet are unaffected.

**Expected effect (estimate, to be measured).** Today about 2 EBs/s are fetched, one at a time at about 0.5 s each.
With `N` in flight the round trips overlap, so roughly `N` times more EBs per second. That holds until the relay's
bandwidth or its limit on outstanding requests caps it.

**Evaluation before D4 is decided.**
- **Acceptance: the full listener callback trace.** The same Musashi range, with and without D4, must give the
  identical sequence of listener calls. That is every `onBlock` with its block and transactions, `batchDone`,
  `onParsingError`, `onEndorserBlockUnavailable` and halt, in order (I5). Comparing successful block hashes alone would
  miss changes to I5.
- **Tests:**
  - **Ordering:** a slow block A with a later block B failing first, in strict and skip mode;
  - **Disconnects:** before the first delivery, and after the wire `BatchDone` but before listener `batchDone`;
  - **Listener behaviour:** a listener that throws; a listener that calls `fetch()` from inside `batchDone`;
  - **Backpressure:** a full queue with a slow front, messages already buffered, and a main-connection keep-alive
    pending;
  - **Large blocks:** a block larger than the byte cap;
  - **Cancellation:** a cancelled request whose reply still arrives;
  - **Side failure:** a side-connection failure with several requests in flight.
- **Speed.** End to end on a fixed range and relay address, at a few values of `N`, with the listener's time
  reported separately.

## Invariants

- **I1.** Listeners see what they see today: the same blocks, order, thread and outcomes (ADR 0016 I1–I3).
- **I2.** A side connection has at most one keep-alive in flight.
- **I3.** A closed side connection sends nothing, and its timer is cancelled.
- **I4.** Within a start, a side connection is replaced only in these cases:
  - it closes, an attempt on it fails, or its keep-alive goes unanswered;
  - it has been idle for 5 minutes;
  - the main connection's address or magic changes.

  Closing an old connection never detaches its replacement.
- **I5 (D4).** A listener receives the same sequence of callbacks, with the same blocks and transactions, with or
  without read-ahead.
- **I6 (D4).** Memory is bounded: what the caps count stays within them, and reading pauses beyond that.

## Tests (D1, D2)

- **Reuse:**
  - two fetches further apart than the old age limit use one connection;
  - keep-alives are sent and answered in between (I2, I4).
- **Unanswered keep-alive:** the peer stops answering. After exactly one ping, the side connection closes, and the next
  fetch succeeds on a new one.
- **Close:** after close, no keep-alive is sent (I3).
- **Idle:** a side connection idle for longer than the limit closes itself on its timer, and the next fetch opens a
  new one (with a short limit in the test).
- **Races:**
  - a tick held across a close and a replacement sends nothing, and leaves the replacement current (I3, I4);
  - retiring an old connection does not detach its replacement (I4).
- **A tick that throws** closes the side connection.
- **A peer that closes the connection:** the next fetch succeeds on a new one (unchanged).

## Verification

- The Musashi check of the Measurement section, with this branch.
  - **Expected:** side connections opened drop from about one per 45 s to about one per main-connection relay
    address.
  - **Expected:** fetches that wait for a new connection drop to about one.
  - **Expected:** blocks/s about the same: the gain is about 1%.
- The unit tests above, and the existing `EndorserBlockResolverTest` and `CompleteBlockAssemblerTest` suites.

### Result (2026-11-21, same check, 15 minutes each)

| | `next` + D1 | this branch (D1 + D2) |
|---|---|---|
| Certifying blocks measured | 1,458 (blocks 9,092–14,180) | 1,295 (blocks 9,092–13,742) |
| Blocks/s | 5.72 | 5.68 |
| Median per certifying block | 503 ms | 510 ms |
| Side connections opened | 25 | 7 |
| Replaced for age | 13 | 0 (the rule is gone) |
| Fetches that waited for a new connection | 23 (10.7 s) | 6 (2.8 s) |
| Main-connection reconnects (public relay) | 9 | 5 |

- **The remaining side connections follow the main connection's reconnects**, each a new start, plus one relay
  address change.
- **No side connection closed itself** for an unanswered keep-alive or for idling.
- **Blocks/s are unchanged,** as expected.
