# ADR 0019: App-Message Submission Throughput and Priority

Date: 2026-11-25

Status: Proposed (revised after design review)

Related: Yano X throughput study for Yano 0.1.0-pre22 (`yano-x/perf-test/RESULTS-2026-11-25.md`).

## Context

Protocol 100 (app-message submission) is a pull protocol modelled on TxSubmission2. The
server (`AppMsgSubmissionServerAgent`) asks the client for message IDs, then for the bodies of
the IDs it has not seen. Its count-based acknowledgements retire the client's oldest
announcements. Yano X uses protocol 100 to diffuse app-chain messages between members,
including its consensus messages on topics that begin with `~consensus/`.

A Yano X throughput study ran 3- and 5-node clusters at up to 3,000 msg/s and found:

1. **The pull window is fixed at 10.** The server caps every request at
   `min(batchSize, MAX_UNACKNOWLEDGED)`, and `MAX_UNACKNOWLEDGED` is a private constant of 10.
   Each round costs two round trips, so a link carries at most 10 messages per 2 RTT: about
   15–20 msg/s at 325 ms RTT.
2. **The client queue is fixed at 1,000 entries and is scanned linearly.**
   `AppProtocolManager` builds the client agent with the default capacity of 1,000. Each of
   these is linear in the queue: the duplicate check, `ConcurrentLinkedQueue.size()`,
   `findMessage`, `removeMessage`. `fillPendingIdsUpToWindow` costs O(queue × offered).
3. **A full queue logs at WARN for every rejected message, and every enqueue and batch logs
   at INFO.** Under overload that is thousands of lines a second.
4. **Consensus messages share the queue with ordinary traffic.** When the queue is full,
   votes and view-change messages are rejected too. In the study a chain stalled
   indefinitely, and one 5-node run collapsed from 2,483 to 201 msg/s.
5. **The validator runs once per message on the event loop.** In Yano X it does Ed25519
   verification, which took 54 % of the inbound event-loop threads.

The design review also found ack-accounting faults in the current code:

- **The server never acknowledges an ID whose body is missing from a reply, and drops the
  outstanding IDs that follow it.** The client has already removed every body it sent, and it
  retires announcements by count, FIFO. A later acknowledgement therefore removes a
  different, never-delivered message.
- **The client can send a second ID reply for the same request.** It changes state only when
  the write of its reply completes. A wake scheduled by `enqueueMessage` before then still
  sees agency and sends the same IDs again. The server then counts them as skipped and
  over-acknowledges.

## Decision

### D1. Configurable pull window and chunked body requests

`AppMsgSubmissionConfig` gains these settings. The defaults keep today's behaviour.

| field | default | meaning |
|---|---|---|
| `maxUnacknowledged` | 10 | upper bound for IDs requested per round; replaces the constant |
| `maxBodiesBytesPerRequest` | 1 MiB | byte budget for one body request |
| `maxPendingMessages` | 1000 | client capacity for ordinary messages |
| `maxPendingPriorityMessages` | 1000 | client capacity for priority messages (D3) |
| `maxPendingPriorityBytes` | unbounded | byte budget (sum of body sizes) for pending priority messages (D3) |
| `priority` | none | predicate that selects priority messages (D3) |

**Request window.** A request asks for `min(batchSize, maxUnacknowledged)` IDs, clamped to
1..32767. Yaci encodes the counts as Java `short`, so a larger value would wrap. `validate()`
warns about out-of-range values instead of the old `batchSize > 10` warning.

**Compatibility with older peers.** A client never replies with more IDs than requested. An
old client answers a larger request. An old server keeps asking for 10. Message formats and
the state machine are unchanged. In `Idle` the server has agency for `MsgRequestMessages` as
well as `MsgRequestMessageIds`, so it may send several body requests in a row.

**Chunked body requests.** The server requests the longest prefix of outstanding IDs whose
announced sizes fit `maxBodiesBytesPerRequest`. That is always at least one ID, so an oversized
message is requested alone. It handles each reply against that chunk only. Then it requests
the next chunk, or, when nothing is outstanding, the next IDs.

**Acknowledgement.**
- **Every requested ID is acknowledged, answered or not.** Acknowledging exactly the IDs
  taken from the client's reply keeps the FIFO accounting aligned.
- **A missing body is skipped, not marked as received**, so a later offer of it is pulled
  again.
- **A body whose size differs from its announced size is rejected as a protocol fault.** The
  check detects a lying peer after its reply has arrived. The byte budget therefore bounds
  replies from honest peers. A hostile peer's reply is bounded only by `window ×
  maxMessageSize` and the decoder.

Two existing server tests asserted the old, misaligning ack and were changed.

### D2. Constant-time client bookkeeping and no duplicate replies

The client keeps undelivered messages in two insertion-ordered maps keyed by message ID: one
for priority messages, one for ordinary ones. Offered-but-unacknowledged IDs are kept as an
`ArrayDeque` plus a `HashSet`.

**Locking.** The agent's own monitor (`this`) guards everything. `Agent.receiveResponse`
already holds it, and `buildNextMessage` and `enqueueMessage` now take it too.
`enqueueMessage` never writes; its wake runs `eventLoop().execute(sendNextMessage)` outside
the lock. As in pre17, a reply that a listener sends from inside `receiveResponse` is
serialized and written on the event loop while the monitor is held. Enqueuing threads wait
for that write but cannot deadlock with it.

**Cost of each operation:**
- enqueue, including duplicate and capacity checks: O(1)
- acknowledge: O(acked)
- ID reply: O(window)
- body reply: O(requested)
- `getQueueSize()`: O(1)

**Lifecycle rules:**
- An offered message leaves the client only by delivery or acknowledgement. If it expires
  after being offered, its body is still sent and the server rejects it. Expiry sweeps
  remove only messages that have not been offered. Each sweep runs from the head of a lane
  to its first unexpired message.
- An ID reply re-lists offered IDs whose bodies were not yet delivered, as today, and then
  adds new IDs up to the requested window.
- A `replyBuilt` flag is set when a reply is built and cleared when the next server request
  arrives. While it is set, `buildNextMessage` returns null and enqueue does not offer, so one
  request gets exactly one reply.

### D3. Priority lane

A message that matches `config.priority` goes into the priority map, which has its own
capacity. A full ordinary lane never rejects a priority message, and a full priority lane
never rejects an ordinary one.

A message is rejected if it would push the priority lane past its count or its byte budget.
The byte budget matters because a priority message can be a whole block proposal.

When the client fills its offer for a server request, it offers new IDs in three passes:
1. priority IDs, up to half the requested window (rounded down);
2. ordinary IDs;
3. more priority IDs, if room remains.

With a window of 2 or more, a faulty sender flooding priority messages therefore cannot
starve ordinary traffic. With a window of 1, the ordinary message goes first. An immediate
wake from a blocking request offers in arrival order; at that moment nothing else is
pending. New offers never exceed the
requested window: the server ignores extra IDs and would never acknowledge them. Acknowledgement stays
FIFO over the order of offers, which is exactly the order the server processes them, so the
server is unchanged.

The lane protects priority messages from rejection; it does not isolate their latency. A
vote enqueued mid-round waits for the round's remaining body chunks. The lane is meant for small control traffic.

### D4. Bounded logging and counters

- Per-message and per-batch logs move to DEBUG.
- A full lane returns `false`, increments `getRejectedQueueFullCount()`, and writes at most
  one WARN per agent every 10 seconds, including the number rejected since the last one.
- Per-message rejections on the server log at DEBUG and are counted in
  `rejectedMessageCount`.
- A reply with omitted bodies is still logged at WARN.

### D5. Batch validation hook

`AppMsgValidator` gains a default method:

```java
default List<Result> validateAll(List<AppMessage> messages)   // default: validate() each, in order
```

The server runs the structural checks first: version, content-derived ID, size, announced
size, TTL and chain. It then passes the surviving messages of one reply to `validateAll` in a
single call.

The default `validateAll` rejects only the message whose `validate` throws. If an
implementation's `validateAll` throws, or returns a list whose length differs from its input,
the whole reply is rejected. The IDs stay acknowledged and the connection stays up. Validation finishes
before listeners run. An implementation that runs validation in parallel must not use the
Netty event-loop group as its executor.

## Consequences

- An embedder that sets nothing keeps today's window and capacities. It gains:
  - quieter logs;
  - constant-time client bookkeeping;
  - correct acknowledgement of omitted bodies;
  - no duplicate ID replies.
- An embedder can raise the window, give control traffic the priority lane, and validate in
  parallel. One link's ceiling then rises from about 20 msg/s to roughly `window / (2 × RTT)`
  (window ÷ two round-trip times), until validation CPU becomes the limit.
- A server holds at most `maxUnacknowledged` outstanding IDs per connection.
- **Mixed versions.** Between an old server and a new client, an omitted body still
  misaligns the acknowledgements. The new client never omits an offered body, so this cannot
  happen on that pairing.
- Not done:
  - pipelining the next ID request alongside body requests (TxSubmission2-style
    non-blocking);
  - a cache of seen IDs shared across connections; the embedder deduplicates across
    connections.

## Testing

Unit tests (`AppMsgSubmissionThroughputTest` plus the updated server tests) cover:
- a window above 10 and the default of 10;
- body chunks by byte budget, acknowledging the whole reply;
- an oversized body requested alone;
- one `validateAll` call per reply, in order;
- rejection of a reply whose result count is wrong;
- priority messages offered first while FIFO acknowledgement stays correct;
- separate lane capacities and the queue-full counter;
- 20,000 pending messages with a window of 500, delivered in order;
- no second reply before the next request;
- offered messages delivered after they expire;
- omitted bodies acknowledged and skipped.

The existing gossip integration test still passes. End to end, the Yano X 3- and 5-node
clusters are re-run with the study's scenarios.
