# ADR 0015: Chain-Sync Server Rolls the Client Back to the Fork Point

Date: 2026-10-11

Status: Accepted

Related: [issue #209](https://github.com/bloxbean/yaci/issues/209). Builds on
[PR #206](https://github.com/bloxbean/yaci/pull/206) (ADR 0013), which reworks how
`ChainSyncServerAgent` answers requests.

## Context

When the server's chain switches fork, `ChainSyncServerAgent` must answer the client's next
request with `MsgRollBackward` to a point on both chains, and then continue on the new fork.
The agent tracks two points:

- `lastSentPoint`, the block the client's read pointer is on.
- `intersectedPoint`, the point found by `FindIntersect`.

Every RollForward sets both to the block it sends. `findRollbackPoint()` returns
`lastSentPoint` if it is still on the chain, otherwise `intersectedPoint`, otherwise it runs a
"deep reorganization" search. A rollback of the newest block therefore invalidates both points
at once, so every rollback goes through the deep search, even a one-block rollback.

The search steps back from `intersectedPoint`'s slot by 10, 50, 100 and 500 slots and asks
`ChainState.getBlockNumberBySlot()` for a block in exactly that slot. On a real network most
slots are empty (preprod produces about one block per 20 slots):

- Usually no probe hits a block, and `handleRollback()` sends `AwaitReply`. That neither moves
  the client off the abandoned fork nor ends the session. Each new block repeats the failed
  search, so the client waits until the connection is restarted. A Yano preprod relay left a
  downstream Haskell node stuck this way for about 50 minutes.
- When a probe does hit a block, it is an arbitrary distance behind the fork point. In the
  same run one rollback sent the client back about 3,500 slots instead of one block.

The existing fork tests pass only because their fixture puts a block in every slot. Their own
comment notes that the rollback lands on block 105 instead of the fork point, 112.

`ChainState.hasPoint()` answers whether a point is on the server's current chain (Yano's
RocksDB chain state checks the slot-to-hash index, and treats `Point.ORIGIN` as present). A
rollback removes the abandoned blocks, so a block sent to the client is still on the chain
exactly when it is at or before the fork point.

## Decision

### D1: Track the client's chain as the points sent to it

Replace `intersectedPoint` with a bounded list of the points on the client's chain, oldest
first:

- An `IntersectFound` replaces the list with the offered points older than the intersection,
  oldest first, followed by the intersection. Those points are on the client's chain, and a
  client that connected at the tip has not been sent any block yet, so without them a rollback
  before its first RollForward would leave no known point. The Haskell node offers its tip and
  points 1, 2, 4, 8 … blocks back. A new intersection also clears `lastSentPoint`, so the next
  `RequestNext` is answered with `MsgRollBackward` to it. `IntersectNotFound` leaves both
  unchanged, because the client's read pointer does not move.
- Each RollForward appends its point. The list keeps the newest 2161 points, however many the
  client offered: a fork point plus 2160 blocks after it, so a rollback of up to `k` = 2160
  (mainnet, preprod; preview's `k` is 432) finds its target. This is a fixed retention policy,
  not each network's `k`: on a network with a larger `k`, a deeper rollback finds no kept point
  and the connection is closed, as below.
- A RollBackward drops the points after its target.

`lastSentPoint` keeps its meaning and its public getter.

### D2: The rollback point is the newest tracked point still on the chain

`findRollbackPoint()` drops points from the end of the list while `chainState.hasPoint()` is
false and returns the last one left. The client's chain and the server's agree up to the fork
point and differ after it, so this is the fork point. A one-block rollback gives a
`MsgRollBackward` to the previous block. The first reply after `FindIntersect` is still a
`MsgRollBackward` to the intersection, now found the same way.

The slot-stepping search, its `getBlockNumberBySlot()` probes and its block parsing are removed.

### D3: Close the connection when no tracked point is on the chain

If none of the tracked points is on the chain, the server cannot name a point the client
holds. This happens when the rollback is deeper than every point the client is known to have:
deeper than `k`, or, for a client that offered a single point and has not been sent a block
since, deeper than that point. (The yaci `ChainsyncAgent` offers a single point.) The server
logs an error, marks the agent `Done` so it writes nothing more, and closes the connection
instead of sending `AwaitReply`. The client reconnects and finds a new intersection with
`FindIntersect`. That is how the stuck peer recovered when the relay was restarted. If its
point is gone, Yano's follower falls back to older points after `IntersectNotFound`. A client
that offered `Point.ORIGIN` is rolled back to it instead, because ORIGIN is always on the
chain.

### D4: No API or wire change

`ChainState` is unchanged, and the agent needs no new configuration. The reply ordering and
request accounting from ADR 0013 are unchanged: a rollback still answers the oldest unanswered
request through `enqueueReply()`.

## Alternatives considered

- **Keep `intersectedPoint` at the `FindIntersect` point.** Always valid in practice, but it
  rolls a long-lived client back to where it connected, possibly days earlier and deeper than
  `k`.
- **Walk the server's chain back by block number.** The server no longer has the abandoned
  blocks, and its current chain cannot tell which of its blocks the client also has, so this
  needs the sent hashes anyway.
- **Pass the rollback point from the node through `onNewDataAvailable()`.** This would add a
  `ChainState` or `Agent` API change for every implementation, and a client that was behind the
  fork point would still need its own point.
- **Fix the slot search** (for example, seek to the nearest stored slot). Still not tied to
  what the client has, so it can return a point after the fork on the new chain.

## Consequences

- After a rollback at the tip, a downstream peer rolls back to the fork point and follows the
  new fork, instead of waiting until the connection is restarted.
- A rollback never sends the client further back than the fork point.
- Each connection holds up to 2161 points, about 300 KB, whatever the client offers.
- In the rare case that no sent point is on the chain, the client reconnects instead of waiting.

## Validation

- The fork tests in `ChainSyncServerAgentConcurrencyTest` now expect the rollback at the fork
  point (block 112) instead of block 105.
- New tests:
  - With one block per 23 slots, a one-block rollback at the tip, with one request waiting, is
    answered with `MsgRollBackward` to the previous block, and the client then follows the new
    fork. On the code before this change the request gets no reply.
  - A client that has not been sent a block yet is rolled back to the newest point it offered
    that is still on the chain, also when the intersection is rolled back before its first
    `RequestNext`.
  - A rollback past every known point closes the connection, and nothing more is written. If
    the client offered ORIGIN it is rolled back to ORIGIN instead.
  - A second `FindIntersect` is followed by `MsgRollBackward` to the new intersection.
- The existing yaci test suite passes.
- Yano, built with this change together with #206 and #208, runs as a preprod relay at the tip
  with a Haskell node downstream. Its rollbacks are checked in the relay log and in the
  downstream node's chain.
