# ADR 0013: Centralize Pipelined Chain-Sync State Transitions

Date: 2026-10-10

Status: Proposed

Risk: Medium — changes to state and agency can stall synchronization or reorder
replies even when the network connection remains healthy.

Related: [issue #205](https://github.com/bloxbean/yaci/issues/205),
[PR #206](https://github.com/bloxbean/yaci/pull/206).

## Context

Yaci normally applies protocol transitions through `State.nextState(Message)`.
`Agent.receiveResponse()` applies the transition before processing the inbound
message. `Agent.sendNextMessage()` calls `sendRequest()` after a successful
asynchronous write. `ChainSyncState` defines the chain-sync states and agency.

The existing transition method has no pending-request or reply-queue context.
For example, `Idle + RequestNext` becomes `CanAwait`, but another inbound
`RequestNext` in `CanAwait` falls through to `Idle`. If a pipelined request
arrives before an earlier write completes, the server can therefore lose agency
while it still has replies to send. Returning to `Idle` after one final reply
also cannot describe all the work remaining in a pipeline.

PR #206 at `45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba` addresses the problem by
tracking unanswered requests, draining queued replies on the channel event loop,
and deriving the server state from its pending work. It overrides
`receiveResponse()`, handles `ChainSyncMsgDone` directly, and assigns states in
`ChainSyncServerAgent.updateState()`. The server no longer calls the enum's
`nextState(Message)`: its receive path is overridden and its writes use a null
success callback, bypassing `Agent.sendRequest()`. Its transition policy has one
owner today, the server agent; the legacy enum table still serves the client.
The motivation for extraction is to give the server policy a named, independently
testable home in the state layer, rather than leave it embedded in network I/O.

The client change in that PR is different: `Rollbackward` releases one
outstanding request, and releases cannot make the count negative. That is
request accounting, not a new client transition table.

This ADR is added to PR #206 before any refactor. Its initial commit changes
documentation only. Acceptance records agreement on the design; it does not
approve an implementation that has not yet been written. Where the eventual
refactor lands is an explicit maintainer question, Q3; adding this ADR does not
decide that the refactor must land on this branch.

## Decision

### D1: Keep transition policy in `ChainSyncState`

Add an internal, pure server-state resolver to `ChainSyncState`. Its inputs are
the previous chain-sync state, an internal event, and an immutable snapshot of
the server's pending work. The snapshot contains the next queued reply's kind
and the number of unanswered `RequestNext` messages. It contains no `Channel`,
`ChainState`, mutable queue, or reference back to an agent.

The event type is a closed internal enum, not `Message`:

| Event | Producer |
| --- | --- |
| `INBOUND_PROCESSED` | The agent has processed a non-`Done` inbound message, including `FindIntersect`. |
| `DONE_RECEIVED` | The agent has recognized `ChainSyncMsgDone`, without processing it as an ordinary request. |
| `DRAIN_COMPLETED` | An actual queue-drain invocation has completed, including an empty drain. |
| `RESET` | Explicit reset has cleared the connection's pending work under the synchronization boundary in D3a. |

`onNewDataAvailable()` and rollback generation update pending work, but introduce
no additional event. Their resulting snapshot is resolved at the existing
inbound/drain boundary. Scheduling a drain off the event loop does not itself
emit `DRAIN_COMPLETED`; that event occurs when the scheduled drain runs.

The resolver owns the following rules, evaluated in this order:

| Condition/event | Result |
| --- | --- |
| `RESET`, after clearing the session's pending work | `Idle` |
| Previous state is `Done`, or event is `DONE_RECEIVED` | `Done` |
| Next queued reply is `IntersectFound` or `IntersectNotFound` | `Intersect` |
| Any other reply is queued | `CanAwait` |
| No reply is queued, but unanswered requests remain | `MustReply` |
| No queued replies or unanswered requests remain | `Idle` |

The queue-derived state describes the server's aggregate work, rather than the
wire phase of every individual pipelined request. Document that distinction in
the resolver. `CanAwait` and `MustReply` retain their existing server-agency
behavior through `ChainSyncState.hasAgency(boolean)`.

Keep the existing `nextState(Message)` signature and sequential/client behavior.
The server must continue to bypass the legacy transition path: the refactor adds
no calls to `nextState()` or `sendRequest()` on the server. Server lifecycle
rules, especially staying in `Done` until reset, move into the resolver. Resolver
and context type names are internal implementation choices; they introduce no
public API requirement.

### D2: Keep connection data and side effects in the agent

Each `ChainSyncServerAgent` continues to own its request count, response queue,
intersection, cursor, and await-reply bookkeeping. Do not introduce a second
copy of those fields in a controller or mutable enum constant.

After processing an inbound request, and after draining the reply queue, the
agent obtains a consistent snapshot and delegates state selection to D1. Route
`Done` and explicit reset through the same policy. One small adapter assigns the
returned state; it contains no message-to-state or queue-to-state rules.
Initialization may set the initial `Idle` state directly.

Retain the existing synchronization around request handling, block-production
notifications, and queue draining. Snapshot construction and state assignment
must use the same synchronization boundary as the associated queue/count
changes. D3a explicitly adds that boundary to reset. Keep actual writes on the
channel event loop and retain the terminal-state check before draining.
Consulting `isDone()` to suppress work is
not a second implementation of the transition policy.

Preserve `onStateUpdate` notification timing: inbound processing notifies after
the final resolved state is available. This refactor does not add notifications
for every outgoing write or change callback ordering.

### D3: Preserve PR #206 behavior and keep the refactor narrow

Preserve unanswered-request accounting, FIFO reply draining, at most one
`AwaitReply` for the oldest waiting request, and terminal-state protection.
Moving the rules must not restore the original agency-gated single-reply drain.

Retain the client's rollback-count fix and existing confirmation semantics.
Client outstanding-request accounting is not the same as the server's
unanswered-request count; do not force both into one shared mutable counter.
Changing client pipeline strategies or the generic `Agent`/`State` contract is
outside this refactor.

Preserve the reviewed server's handling of received `Done`. This extraction does
not introduce stricter rejection of out-of-order peer messages. Such protocol
validation requires a separate decision and compatibility tests.

In particular, receiving `FindIntersect` resets `unansweredRequests` and the
await-reply flag in the baseline, even when the peer sends it with outstanding
requests. Treat it as a new request-accounting interval: prior unanswered
obligations are discarded without final replies. The existing response queue is
not cleared. Preserve this permissive behavior without declaring the sequence
protocol-legal or extending I2 to it. After processing the intersection, the
resolver returns `Intersect` if the intersect reply is first in the queue, or
`CanAwait` if an older `AwaitReply` is still at its head. Queue draining then
follows the existing FIFO order. Capture both cases in M1.

### D3a: Serialize explicit reset with live state changes

The pinned `ChainSyncServerAgent.reset()` is not synchronized. Requiring reset to
use the same agent monitor as receive, notification, and drain operations is an
intentional concurrency change, not preserved baseline behavior. It makes queue
clearing, counter/cursor reset, snapshot construction, and assignment of `Idle`
one atomic operation relative to those operations. Its public signature remains
unchanged. A concurrent-reset test is required in M2; equality with the old
unsynchronized interleaving is not the oracle for this case.

## Invariants

I1–I3 and I5 apply to protocol-legal request streams within an accounting interval
bounded by `FindIntersect`, reset, or termination. D3 separately specifies the
compatibility behavior for an out-of-order `FindIntersect`; preserving that
behavior does not promise to answer requests it abandons.

| ID | Required property | Decisions |
| --- | --- | --- |
| I1 | A second pipelined request cannot make a live server with queued replies lose agency or leave those replies waiting for another request. | D1, D2, D3 |
| I2 | For protocol-legal sequences within one request-accounting interval, each accepted `RequestNext` is either still unanswered or has generated one final reply. `AwaitReply` does not consume that obligation. `FindIntersect`, reset, and termination end the interval. | D2, D3 |
| I3 | Final replies are generated and submitted in request order. Producer-thread notifications cannot overtake replies already queued. | D2, D3 |
| I4 | After `Done`, drains and notifications emit no further replies and cannot leave `Done`. Only explicit reset starts a new session. | D1, D2, D3a |
| I5 | With no unanswered requests and no queued replies, a data notification emits no unsolicited reply. Repeated notifications do not duplicate a final reply. | D2, D3 |
| I6 | Every connection has independent mutable context; enum constants contain no connection-specific mutable fields. | D1, D2 |
| I7 | A client rollback reply releases one outstanding slot when one exists; counts cannot underflow. Roll-forward confirmation timing stays unchanged. | D3 |
| I8 | Public signatures, wire encoding, enum constants, and listener ordering remain compatible with the reviewed implementation. | D1, D2, D3 |

These invariants concern protocol bookkeeping and submission of replies. They do
not turn a successful local write into durable consumer receipt. Reconnects and
rollbacks can replay chain data; this ADR introduces no exactly-once processing
or unconditional at-least-once callback-delivery guarantee.

## Alternatives considered

1. **Keep `updateState()` and direct `Done` assignment in the server.** This
   remains a workable bug fix with one server-policy owner, but leaves that
   policy embedded in an I/O class instead of independently testable in the
   state layer.
2. **Return to `nextState(Message)` unchanged.** Rejected because it lacks the
   pending-work context needed to prevent the issue #205 interleaving.
3. **Change only `CanAwait + RequestNext` to stay in `CanAwait`.** Insufficient:
   it still cannot distinguish the last reply from replies with more requests
   outstanding, or represent queue-drain and lifecycle events.
4. **Introduce a separate mutable server-state controller immediately.** It
   could work if it became the sole owner of the session context, but requires
   relocating lifecycle and accounting responsibilities as well as the rules.
   Prefer the smaller pure resolver for this extraction.
5. **Replace the generic state/agent framework for every protocol.** Too broad
   for this refactor; it increases the regression surface for unrelated users.

## Compatibility and trust assumptions

The peer and timing of inbound messages are untrusted. Socket writes complete
asynchronously, and new-data notifications can originate on producer threads.
Tests must control those schedules rather than assume immediate completion.
The context snapshot is internal, with nonnegative counts maintained by the
agent; it is not a new peer-supplied object. No secrets or persisted data formats
are introduced.

Keep `State.nextState(Message)`, `hasAgency(boolean)`, `getCurrentState()`,
`confirmBlock(Point)`, reset entry points, and the deprecated
`notifyNewBlock(Point)` callable with their current signatures. Do not remove or
rename enum constants. No wire, configuration, database, or Java-version change
is required by this design. Existing library upgrade requirements still apply.

D3a intentionally strengthens reset concurrency. D3 retains the existing
out-of-order intersection behavior; strict rejection remains deferred under Q1.
The baseline comparisons therefore cover ordinary protocol behavior and the
explicit D3 compatibility traces, while D3a has its own atomicity expectations.

The normal helper path invokes later-registered application listeners before
its internal listener because `Agent.addListener()` prepends entries. Preserve
that ordering; moving confirmation ahead of application callbacks would be a
separate behavioral change.

## Verification and implementation milestones

### M1: Agree on the design and establish the baseline

Entry: this ADR is available for review against the pinned implementation below.
Exit: the design is accepted for implementation and the following behavior is
captured before moving the rules (I1–I8):

- Record expected state/agency results from the table in D1, independently of
  the proposed resolver, including reset, `Done`, and conflicting priorities.
- Retain `ChainSyncServerAgentConcurrencyTest` and
  `ChainsyncAgentPipeliningTest` as integration-level regression coverage.
- Record observable `onStateUpdate` ordering for inbound events.
- Capture D3's two out-of-order intersection traces: two requests parked at tip
  followed by `FindIntersect`, with either the earlier `AwaitReply` already
  drained or still queued. Assert the discarded obligations, `Intersect` versus
  `CanAwait` before draining, FIFO output, and no later final replies for the
  abandoned requests. These characterize permissive compatibility, not I2.

### M2: Extract the policy and integrate the server

Entry: M1 is complete and the maintainer has selected the implementation PR
under Q3. Exit: the resolver and delegation satisfy I1–I8 within their stated
scope, preserve D3's explicit compatibility traces, and satisfy D3a's reset
atomicity test, with no transition table left in the agent.

Add focused tests for the independently specified D1 table, two independent
connections, and a deferred drain after `Done`. Exercise delayed write
completion, multiple requests parked at tip, unavailable header data, repeated
notifications, rollback to a point and to Origin, and reset. Compare emitted
message order and state/agency observations with the pinned baseline for valid
sessions; assert explicit expected traces as well so a shared bug cannot pass
solely through differential comparison.

Test all four named events and their priority, including a nonempty snapshot
with `DONE_RECEIVED` and an empty snapshot with `RESET` from `Done`. Hold a live
operation under the agent monitor while another thread invokes reset: reset
must not interleave its clears/assignment with that operation. After reset has
completed, subsequent operations see the complete cleared context. Exercise
reset against both producer notifications and event-loop drains, using latches
instead of timing sleeps. This tests the intentional D3a change directly.

Preserve the existing real-fork and rollback-without-new-blocks fixtures. Keep
the real `DefaultEventLoop` ordering test; a stub that always completes writes
inline is insufficient evidence for I1 and I3.

### M3: Verify downstream behavior before merging a refactor

Entry: M2 passes. Exit: run the relevant core tests and `./gradlew build`, then
repeat compatibility checks using the exact refactored artifacts:

- yaci-store's `BlockSync` path: catch-up, slow callbacks, live blocks, forks,
  rollback, and reconnect from a known point.
- Yano's chain-sync integration: pipelined operation, pause/resume, rollback,
  reconnect, and continued progress after a pause without forced reconnection.
- Source and binary compatibility for `notifyNewBlock(Point)` and existing
  client entry points; sequential, legacy-pipelined, and enhanced modes.

Record revisions, commands, actual results, and skipped/unavailable scenarios.
Previous PR #206 test results establish a baseline, not validation of a future
refactor. Failed-write recovery and durable consumer checkpoints need their own
failure testing before any stronger delivery guarantee is claimed.

M2–M3 gate the PR containing the refactor. If Q3 selects a follow-up PR, they do
not hold up merging the existing #206 bug fix plus this ADR. If Q3 selects #206,
both reviewers must re-review the new implementation SHA after these gates pass.

## Consequences, risks, and audit gates

Transition policy becomes reviewable in one place while agents retain the data
and I/O needed to execute it. The cost is an explicit distinction between the
legacy message transition and the server's aggregate-state resolver.

Principal risks are stale context snapshots, accidentally adding the legacy
transition path to the server, moving state changes across asynchronous write
boundaries, and changing notification ordering. M1–M3 must verify those
boundaries. Audit that the server diff adds no `nextState()` or `sendRequest()`
calls, leaves no direct state-selection branches in the agent, and adds no
mutable fields to enum constants. Account explicitly for the D3a concurrency
change. Preserve the issue #205 reproductions as merge gates; a successful build
alone is insufficient.

No runtime refactor is included with this initial ADR. A later implementation
commit needs review at its own SHA. Existing approval of the pinned PR head
does not certify this new design or a later implementation.

## Open questions and related work

- **Q1: Strict invalid-message handling.** Options are to retain permissive
  behavior or explicitly reject illegal `Done`/intersection sequences. D3
  chooses compatibility for this refactor; whether to add strict validation
  remains open for a separate change.
- **Q2: Generalize to other protocols.** Options are to retain this small
  chain-sync-specific resolver or introduce a shared context-aware state API.
  Prefer the first until another protocol demonstrates the same need. No shared
  framework migration is required before this ADR can be implemented.
- **Q3: Which PR carries the refactor? Open, pending maintainer choice.**
  Option (a) merges #206's existing fix and the ADR, then implements M2–M3 in a
  follow-up PR citing this ADR and the `45d0728` baseline. Option (b) adds the
  refactor to #206 and requires fresh implementation approval at the new SHA
  plus M3 downstream results before merging it. Prefer (a) so the production
  fix can merge independently and the refactor has its own review and merge.
  This open sequencing question does not block ADR design acceptance, but must
  be resolved before M2 begins. No merge or refactor is authorized by recording
  the alternatives.
- Coarse rollback-point selection, failed-write retry policy, and downstream
  crash-safe processing are related concerns outside this extraction.

## Pinned implementation references

The compatibility baseline is Yaci commit
`45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba`. These source sections were inspected
for this proposal; they specify existing behavior, not an independent Cardano
protocol specification:

- [Agent: receive/send transitions and listener registration](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/Agent.java)
- [ChainSyncState: state transitions and agency](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/chainsync/n2n/ChainSyncState.java)
- [ChainSyncServerAgent: receiveResponse, updateState, sendNextMessage, reset](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/chainsync/n2n/ChainSyncServerAgent.java)
- [ChainsyncAgent: rollback and confirmation accounting](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/main/java/com/bloxbean/cardano/yaci/core/protocol/chainsync/n2n/ChainsyncAgent.java)
- [Server concurrency and fork regressions](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/test/java/com/bloxbean/cardano/yaci/core/protocol/chainsync/ChainSyncServerAgentConcurrencyTest.java)
- [Client rollback accounting regression](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/test/java/com/bloxbean/cardano/yaci/core/protocol/chainsync/ChainsyncAgentPipeliningTest.java)

## Revision history

- **r1 (2026-10-10):** Initial proposal requested during reviewer2's follow-up
  discussion on PR #206. Records centralized state policy and the validation
  gates to meet before refactoring the reviewed implementation.
- **r2 (2026-10-10; responds to reviewer1's review of
  `f4e5f14588804718f3e96a4ec90e5d3d5f0f6f57`):** F6 scopes I2 and adds the
  out-of-order intersection compatibility traces to D3/M1; F7 records Q3 and
  its implementation gates; F8 corrects the policy-ownership description and
  forbids adding the legacy server transition path; F9 defines the four events
  and records synchronized reset as intentional D3a behavior with direct tests.
