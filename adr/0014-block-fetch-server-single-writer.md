# ADR 0014: Block-Fetch Server Writes From One Thread

Date: 2026-10-11

Status: Accepted

Related: [issue #207](https://github.com/bloxbean/yaci/issues/207). Found while validating
[PR #206](https://github.com/bloxbean/yaci/pull/206) in Yano.

## Context

`BlockFetchServerAgent` answers `RequestRange` with `StartBatch`, one `MsgBlock` per block and
`BatchDone`, or with `NoBlocks`. A single-thread executor runs the batch loop, `processNext()`,
so store reads do not block the channel's event loop. `Agent.writeMessage()` splits a message
larger than 65535 bytes into several mux segments, writing each with its own `writeAndFlush`.

Netty keeps the order of writes made by one thread. A write from a thread other than the
event loop is queued as a task, while a write made on the event loop runs at once. Writes
from two threads therefore have no defined order between them.

The agent writes block-fetch messages from two threads:

- The executor writes from the batch loop. Once the channel is not writable, `sendToClient()`
  queues messages in `pendingMessages`. When the channel is writable it writes at once, even if
  earlier messages are still queued.
- On the event loop, `onChannelWritabilityChanged()` drains `pendingMessages` and then calls
  `processNext()`, which runs the next batch, including its store reads, on the event loop.

As a result, newer messages can overtake older ones that are still queued. A message written
on the event loop can also land between two segments of a large block that the executor has
queued. With Netty's default 64 KB high-water mark, one large block is enough to make the
channel not writable, so this happens on preprod and mainnet but rarely on devnet.

A downstream Haskell node then fails to decode the block-fetch stream. It reports
`DecoderFailure` with messages such as `codecBlockFetch (ServerHasAgency, SingBFStreaming)
unexpected key`, `expected string` and `expected bytes`, or `WrongBlock`, and drops the
connection. A Yano relay in the #206 validation logged 140 such failures on preprod and 22 on
mainnet. Released yaci `0.5.0-pre13` shows the same failures.

The batch loop's scheduling has two more defects in the same code:

- `processNext()` re-submits itself from `finally` even when the request queue is empty. After
  its first request, an idle agent keeps one executor thread busy until the connection closes.
  A probe measured 1981 ms of CPU in 2 s of wall time.
- When `processNext()` finds the channel not writable, it clears `processing` and returns. If
  the channel becomes writable between that check and the clear, `onChannelWritabilityChanged()`
  still sees `processing` set and does not resume. The queued requests then wait for the next
  request or writability change.

`maybeStartProcessing()` and `processNext()` are `synchronized` on the agent. The event loop
calls `maybeStartProcessing()` for every `RequestRange`, so it can block for the length of a
batch the executor is running.

## Decision

### D1: The executor is the only writer

All block-fetch server messages are written by the agent's executor thread:

- `sendToClient()` always writes. It no longer checks writability or bypasses a queue.
- `pendingMessages` is removed. Netty's outbound buffer already queues writes in order while the
  channel is not writable.
- `onChannelWritabilityChanged()` writes nothing and does not run `processNext()`. When the
  channel is writable it calls `maybeStartProcessing()`, which hands the work to the executor.

Netty keeps one thread's writes in order. With a single writer, block-fetch messages reach the
peer in the order the batch loop produced them, and the segments of a large block stay
together. Writes from other mini-protocols may still sit between them, which the mux allows.

### D2: Keep the existing backpressure behaviour

The batch loop still checks writability once, before it starts a batch, and defers while the
channel is not writable. Within a batch it writes every message. Before this change the rest
of the batch went into `pendingMessages`, and the event loop moved it to Netty's outbound
buffer one message at a time while the channel was writable. Now it goes into the outbound
buffer straight away, as encoded buffers rather than `Message` objects. Either way the amount
held is bounded by the range the client requested, and the client limits its bytes in flight.
No new limit or setting is added. That bound relies on an honest client: a peer that requests
large ranges without reading them can still make the server hold them in memory. Bounding that
is left to a follow-up.

If the channel closes during a batch, the loop stops reading blocks. Without this check every
remaining block would be read and its write rejected with a warning, which the old queue
hid.

### D3: Schedule the batch loop without spinning or lost wake-ups

- `maybeStartProcessing()` submits `processNext()` only when no run is active and the request
  queue is not empty.
- After a batch, or after finding the queue empty, `processNext()` clears `processing` and calls
  `maybeStartProcessing()`. An idle agent therefore stops, and a request that arrived during the
  run still starts the next one.
- When it defers because the channel is not writable, `processNext()` clears `processing` and
  then checks writability again. A writability change that happened before the clear is not
  lost.
- `maybeStartProcessing()` and `processNext()` are no longer `synchronized`. The
  `processing` flag and the single-thread executor already allow only one run, and the event
  loop no longer waits for a batch to finish.

### D4: No change to `Agent`

This change does not touch `Agent`'s write path. The segments in #207 were split because two
threads wrote block-fetch messages; after D1 one thread writes them, so each message's segments
are queued together and in order. (`Agent`'s write path was later reworked for a lock-order
deadlock in #226; that does not change this.) The other server agent that sends messages larger
than one segment, `AppChainSyncServerAgent` (`MsgBlocks`), already writes only on the event
loop, so its segments are written together. If another agent later writes large messages from
more than one thread, the fix belongs in that agent's write path, as here.

## Alternatives considered

- **Write every block-fetch message on the event loop.** This would also give a single
  writer, but it would need a second queue and a hand-off for each message. The executor
  already orders the batch, so it is simpler for the executor to write.
- **Make segmented writes atomic in `Agent`**, for example by submitting all of a message's
  segments as one event-loop task. That would fix interleaving but not the reordering in
  `sendToClient()`, and it would change write timing for every mini-protocol. It is not needed
  once D1 holds.
- **Keep `pendingMessages` and drain it only from the executor.** This duplicates Netty's
  outbound buffer and needs its own wake-up handling. It adds code and gives nothing back.

## Consequences

- Block-fetch replies are in protocol order and their segments are not split, so a downstream
  Haskell or Yano node no longer drops the connection with decode errors.
- Store reads no longer run on the event loop, and the event loop no longer waits on the agent's
  monitor.
- An idle connection no longer keeps an executor thread busy, and a closed connection's executor
  thread is stopped.
- The public API, configuration and wire format are unchanged.

## Validation

- `BlockFetchServerAgentConcurrencyTest.pipelinedBatchesStayInOrderAndUninterleavedUnderBackpressure`
  runs 20 pipelined ranges of 5 blocks (20 KB to 200 KB each) over a Netty local channel. Each store read makes the channel not
  writable, and the event loop makes it writable again shortly after, as a slow peer would. The
  test reassembles the block-fetch segments the peer receives and compares them with the
  expected batches. On the code before this change it failed in 3 of 3 runs, either at byte
  65537, right after the first segment of the first block, or by stalling. It passes with the
  change. Its power to detect the old defect depends on thread timing; with the change it
  passes deterministically, because only one thread writes.
- `idleAgentStopsPollingOnceItsRequestsAreAnswered` checks that an agent whose requests are
  answered stops touching the channel. It fails on the old code, which kept polling.
- `disconnectStopsTheWorkerThread` checks that a disconnect stops the agent's executor thread
  and that a request arriving after it is dropped.
- The existing yaci test suite passes.
- Yano is built against this change and run as a preprod relay that syncs from a Haskell node,
  with a Haskell node downstream. Compared with the #206 validation run, the downstream's
  `DecoderFailure` exits went from 140 to 0 and its `WrongBlock` exits from 3 to 0. It synced
  from block 1,028,420 to the tip (5,278,547) with a tip hash matching the relay's, and relay
  CPU at the tip stayed under 1%.
