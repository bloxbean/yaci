# ADR 0017: Leios side connection: measure the endorser block fetch, keep one connection alive

- Status: Proposed
- Date: 2026-11-21
- Risk: low.
  - Only a Dijkstra block that certifies an endorser block (EB) uses the side connection. Preview, preprod and
    mainnet never open one.
  - What reaches listeners, and when, does not change: the same blocks, in the same order, with the same outcomes
    (ADR 0016 D3).
- Amends: ADR 0016 D4a (reuse of the side connection). Everything else in ADR 0016 stands.
- Issue: #255, and the review discussion there.

## Context

To complete a certifying block, Yaci fetches the EB that the block certifies, and then the EB's transactions, over a
side connection to the main connection's relay (ADR 0016 D4a). That connection carries only the handshake and
LeiosFetch. It sends no keep-alive, and a relay drops a connection that sends no keep-alive for about 97 s. ADR 0016
handled this by never keeping a side connection long enough to reach that limit: before a fetch, it replaces one that
is older than 45 s, or that has been idle for 30 s.

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
| Share of the wall time spent inside EB fetches | 89% (792 s of 889 s) |
| Time per certifying block, median | 503 ms: EB request 242 ms, transactions 245 ms, mapping 16 ms |
| Throughput | 5.7 blocks/s, 1.64 EBs/s, 1,619 transactions/s, 0.41 MB/s |
| Transaction batches per EB | always 1 (about 1,035 transactions, 257 KB; the batch limit is 500 KB) |
| Side connections opened | 25: 13 replaced for being older than 45 s, 2 after the relay address changed |
| Fetches that waited for a new side connection | 23 of 1,458, about 477 ms each: 10.7 s, 1.2% of the wall time |
| Round trip to the relay's 8 addresses (TCP connect) | 170–280 ms |

What the numbers show:
- **The sync is bound by round trips.** Each certifying block costs two of them, one after the other (the EB, then its
  transactions), while the main connection waits (ADR 0016 D3b). That is 89% of the time.
- **Replacing the side connection costs about 1%.** Reusing it is worth doing for the relay's sake and for fewer
  handshakes, but it is not what makes Musashi slow.
- **In this range every EB fits one transaction batch.** Sending batches without waiting for each reply would gain
  nothing here. It is left out of this ADR (see D3).

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
  `BlockRangeSync` left open after `batchDone` does not keep a side connection forever.
- **Unchanged from ADR 0016 D4a.** The connection is still replaced when it closes, when an attempt on it fails, or
  when the main connection's address or network magic changes.
- **The timer.** One shared daemon scheduler (`yaci-leios-keepalive`) runs every side connection's timer. A
  connection's timer is cancelled when it closes, for any reason, so a closed connection sends nothing.

### D3. Not in this ADR

- **Fetching on the parent's announcement:** withdrawn. Yaci only asks for certified EBs (ADR 0013a).
- **Several transaction requests in flight:** not needed while EBs fit one batch. If larger EBs need several
  batches, the model is `leiosFetchClientPeerPipelined` in ouroboros-consensus `840e789b` (the w42 pin, #255).
- **Overlapping the fetches of several certifying blocks** (bounded read-ahead in `BlockRangeSync`): this is the only
  change that addresses the 89%. It changes the delivery contract of ADR 0016 D3b, so it needs its own ADR. That ADR
  must cover the reconnect cursor, backpressure by blocks and bytes, `batchDone` order, `onParsingError` and listener
  exceptions, `fetch()` admission while blocks are queued, and rollback and restart (#255).

## Invariants

- **I1.** Listeners see what they see today: the same blocks, order, thread and outcomes (ADR 0016 I1–I3).
- **I2.** A side connection has at most one keep-alive in flight.
- **I3.** A closed side connection sends nothing, and its timer is cancelled.
- **I4.** Within a start, a side connection is replaced only when it closes, an attempt on it fails, its keep-alive
  goes unanswered, it has been idle for 5 minutes, or the main connection's address or magic changes.

## Tests

- **Reuse:**
  - two fetches further apart than the old age limit use one connection;
  - keep-alives are sent and answered in between (I2, I4).
- **Unanswered keep-alive:** the peer stops answering. The side connection closes, and the next fetch succeeds on a
  new one.
- **Close:** after close, no keep-alive is sent (I3).
- **A peer that closes the connection:** the next fetch succeeds on a new one (unchanged).
- **Idle:** a side connection idle for longer than the limit is replaced at the next fetch (with a short limit in the
  test).

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

- **The remaining side connections follow the main connection's reconnects**, each a new start, plus one relay address change.
- **No side connection closed itself** for an unanswered keep-alive or for idling.
- **Blocks/s are unchanged,** as expected (see D3).
