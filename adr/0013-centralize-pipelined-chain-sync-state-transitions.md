# ADR 0013: Pipelined Chain-Sync State Ownership and Regression Contract

Date: 2026-10-10

Status: Proposed

Related: [issue #205](https://github.com/bloxbean/yaci/issues/205),
[PR #206](https://github.com/bloxbean/yaci/pull/206).

The original filename is retained so links in the review history keep working.

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
The existing tests already exercise that policy through a stub channel and
assert its state and reply traces. There is no second consumer of the policy and
no identified property that requires a separate resolver to test it. Review of
the initial extraction proposal therefore favors documenting the current
contract rather than introducing another abstraction.

The client change in that PR is different: `Rollbackward` releases one
outstanding request, and releases cannot make the count negative. That is
request accounting, not a new client transition table.

The maintainer requested this ADR in PR #206 before refactoring. The decision
after review is to retain the existing agent-owned policy and record the
regression contract. No runtime refactor is selected by this revision. D4
records the document-placement decision; Q3 covers where a future change would
land if a concrete need reopens the extraction question.

## Decision

### D1: Context-aware resolver (not selected in r3)

The r1/r2 proposal extracted a pure resolver into `ChainSyncState`, together
with an event enum, context snapshot, and agent adapter. Review found that its
policy already has one owner and can be tested through the agent. The two
ordinary events also selected identical outcomes. Retain this decision ID for
review history; D1a supersedes the extraction proposal.

### D1a: Keep the server policy in the agent and record its contract

Choose Alternative 1. Keep `ChainSyncServerAgent.updateState()`, its received
`Done` handling, and the direct `reset()` initialization. Introduce no resolver,
event enum, snapshot type, or adapter. `ChainSyncState` continues to define the
state values, agency, and the client's message transitions. The server derives
its aggregate state from the pending work it owns; it does not use the legacy
single-request transition table to account for a pipeline.

The following numbered rows describe the existing behavior; they are a review
and regression contract, not a new transition API:

| Row | Condition/action | Result |
| --- | --- | --- |
| S1 | Explicit `reset()` clears the connection context | `Idle` |
| S2 | Receive `ChainSyncMsgDone`, or reconcile state after already entering `Done` | `Done` |
| S3 | In a live session, next queued reply is `IntersectFound` or `IntersectNotFound` | `Intersect` |
| S4 | In a live session, any other reply is queued | `CanAwait` |
| S5 | No reply is queued, but unanswered requests remain | `MustReply` |
| S6 | No queued replies or unanswered requests remain | `Idle` |

S1 is a direct explicit lifecycle operation, not a reset event passed through a
resolver. `updateState()` applies S2's terminal guard before S3–S6. `CanAwait`
and `MustReply` retain server agency through `ChainSyncState.hasAgency(boolean)`.
The derived state describes aggregate server work rather than every individual
request's wire phase.

Keep `nextState(Message)` and client behavior unchanged. The server continues
to bypass `nextState()` and `sendRequest()`; writes use
`writeMessage(response, null)`. Do not change the generic `Agent`/`State`
contract or other mini-protocols to implement this decision.

Revisit extraction only when there is a concrete need, such as a second
consumer of the same server policy or an important property that cannot be
exercised through the agent. Architectural symmetry alone is insufficient.

### D2: Retain per-connection data and I/O ownership

Each server agent keeps its request count, response queue, intersection,
cursor, and await-reply bookkeeping. Enum constants have no connection-specific
mutable fields. Retain the existing synchronized receive, notification, and
drain operations, FIFO event-loop writes, and terminal guard before draining.

Preserve `onStateUpdate` timing: inbound processing notifies after the final
state is available. Do not add notifications for each outgoing write or change
listener ordering. Server connections get separate agents from
`NodeServerSession`; no new shared controller or context store is introduced.

### D3: Preserve PR #206 behavior and make the regression contract explicit

Preserve unanswered-request accounting, FIFO reply draining, at most one
`AwaitReply` for the oldest waiting request, and terminal-state protection.
When that oldest request cannot be answered and has not yet had an
`AwaitReply` queued, enqueue exactly one during that answering pass (I9). Drain it
without requiring another inbound request. A once-per-session `AwaitReply` is
insufficient. Keep the ordered drain rather than the original agency-gated
single-reply drain.

Retain the client's rollback-count fix and existing confirmation semantics.
Client outstanding-request accounting is not the same as the server's
unanswered-request count; do not force both into one shared mutable counter.
Changing client pipeline strategies or the generic `Agent`/`State` contract is
outside this decision.

Preserve the reviewed server's handling of received `Done`. This decision does
not introduce stricter rejection of out-of-order peer messages. Such protocol
validation requires a separate decision and compatibility tests.

In particular, receiving `FindIntersect` resets `unansweredRequests` and the
await-reply flag in the baseline, even when the peer sends it with outstanding
requests. Treat it as a new request-accounting interval: prior unanswered
obligations are discarded without final replies. The existing response queue is
not cleared. Preserve this permissive behavior without declaring the sequence
protocol-legal or extending I2 to it. After processing the intersection, the
agent selects `Intersect` if the intersect reply is first in the queue, or
`CanAwait` if an older non-intersect reply, such as `AwaitReply`, is still at its
head. Queue draining then
follows the existing FIFO order. Capture both cases in M1.

### D3a: Synchronized reset proposal (withdrawn in r3)

Keep `reset()`'s current direct assignment and clearing behavior. Do not add
synchronization or a reset event. In the pinned repository, the server creates
a fresh agent per connection and has no production call to this server reset
method; generic reset calls belong to the client connection path. External
callers have not been inventoried. This ADR adds no guarantee for concurrently
resetting a live server agent. If a production caller needs that behavior,
decide its concurrency contract then, with evidence for that caller.

### D4: Keep this requested ADR in PR #206

The maintainer explicitly requested an ADR "in this PR before refactoring" and
then assigned the author to complete its review with reviewer1 and reviewer3.
Retain this document in #206 as that requested design record. This is a
conscious addition to the fix PR, not an assumption that `next` already tracks
an ADR series or that a refactor belongs in the same PR.

`main` and `next` currently have no tracked `adr/` tree. Historical commit
`cdd7a79c9e4dcd096297ae0bbc4ae6edeecf08e0` removed UTxO ADRs after they moved to
other projects. The maintainer has since confirmed that Yaci ADRs belong in
`adr/`, as recorded in [reviewer3's placement follow-up](https://github.com/bloxbean/yaci/pull/206#issuecomment-6098182089).
This record starts that tracked series without restoring the removed UTxO ADRs.

Number 0013 avoids numbers 0001–0012 already used by local records and feature
branches; those earlier records are not asserted to exist on `next`.

## Invariants

I1–I3, I5, and I9 apply to protocol-legal request streams within an accounting
interval bounded by `FindIntersect`, reset, or termination. D3 separately specifies the
compatibility behavior for an out-of-order `FindIntersect`; preserving that
behavior does not promise to answer requests it abandons.

| ID | Required property | Decisions |
| --- | --- | --- |
| I1 | A second pipelined request cannot make a live server with queued replies lose agency or leave those replies waiting for another request. | D1a, D2, D3 |
| I2 | For protocol-legal sequences within one request-accounting interval, each accepted `RequestNext` is either still unanswered or has generated one final reply. `AwaitReply` does not consume that obligation. `FindIntersect`, reset, and termination end the interval. | D2, D3 |
| I3 | Final replies are generated and submitted in request order. Producer-thread notifications cannot overtake replies already queued. | D2, D3 |
| I4 | After `Done`, drains and notifications emit no further replies and cannot leave `Done`. Only explicit reset starts a new session. | D1a, D2 |
| I5 | With no unanswered requests and no queued replies, a data notification emits no unsolicited reply. Repeated notifications do not duplicate a final reply. | D2, D3 |
| I6 | Every connection has independent mutable context; enum constants contain no connection-specific mutable fields. | D1a, D2 |
| I7 | A client rollback reply releases one outstanding slot when one exists; counts cannot underflow. Roll-forward confirmation timing stays unchanged. | D3 |
| I8 | Public signatures, wire encoding, enum constants, and listener ordering remain compatible with the reviewed implementation. | D1a, D2, D3 |
| I9 | When the oldest unanswered request cannot be answered and no `AwaitReply` has yet been queued for it, enqueue exactly one in that answering pass and drain it without waiting for another request. After its final reply, apply the same rule to the next oldest request. | D1a, D2, D3 |

These invariants concern protocol bookkeeping and submission of replies. They do
not turn a successful local write into durable consumer receipt. Reconnects and
rollbacks can replay chain data; this ADR introduces no exactly-once processing
or unconditional at-least-once callback-delivery guarantee.

## Alternatives considered

1. **Keep `updateState()` and direct `Done` assignment in the server. Chosen.**
   One policy owner, already covered by state/trace assertions through the
   agent. Record its contract and extraction triggers without new types.
2. **Return to `nextState(Message)` unchanged.** Rejected because it lacks the
   pending-work context needed to prevent the issue #205 interleaving.
3. **Change only `CanAwait + RequestNext` to stay in `CanAwait`.** Insufficient:
   it still cannot distinguish the last reply from replies with more requests
   outstanding, or represent queue-drain and lifecycle events.
4. **Introduce a separate mutable server-state controller immediately.** It
   could work if it became the sole owner of the session context, but requires
   relocating lifecycle and accounting responsibilities as well as the rules.
   Neither that migration nor a pure resolver is justified by a current need.
5. **Replace the generic state/agent framework for every protocol.** Too broad
   for this decision; it increases the regression surface for unrelated users.
6. **Derive `hasAgency()` from owed work and retain only `Idle`/`Done` state.**
   Rejected: `getCurrentState()` is public and its intermediate states are
   asserted by tests. Also, `Agent.hasAgency()` is final, so implementing this
   option would expand the change into the generic agent contract.

## Compatibility and trust assumptions

The peer and timing of inbound messages are untrusted. Socket writes complete
asynchronously, and new-data notifications can originate on producer threads.
Tests must control those schedules rather than assume immediate completion.
Pending-work counts and the reply queue are internal to the agent.

Keep `State.nextState(Message)`, `hasAgency(boolean)`, `getCurrentState()`,
`confirmBlock(Point)`, reset entry points, and the deprecated
`notifyNewBlock(Point)` callable with their current signatures. Do not remove or
rename enum constants. No wire, configuration, database, or Java-version change
is required by this design. Existing library upgrade requirements still apply.

D3 retains the existing out-of-order intersection behavior; strict rejection
remains deferred under Q1. Reset behavior is unchanged under withdrawn D3a.

The normal helper path invokes later-registered application listeners before
its internal listener because `Agent.addListener()` prepends entries. Preserve
that ordering; moving confirmation ahead of application callbacks would be a
separate behavioral change.

## Verification and milestones

### M1: Review the contract against the existing implementation

Entry: this ADR is available against the pinned code. Exit: reviewers accept
D1a–D4, S1–S6, and I1–I9 with the named existing tests and D3 traces as evidence.
Design acceptance does not authorize an unselected refactor.

At the pinned baseline, the two focused classes have 18 committed tests:
17 in `ChainSyncServerAgentConcurrencyTest` and 1 in
`ChainsyncAgentPipeliningTest`. Retain these tests unchanged, especially:

- `pipelinedRequestsAtTip_eachGetsExactlyOneReply`: the explicit
  `AW, RF, AW, RF, RF` trace is I9's oracle, along with state and count behavior.
- `pipelinedRequestsBeforeWriteCompletion_noReplyStranded` and
  `repliesQueuedOffTheEventLoop_areWrittenOnTheEventLoopInOrder`: held write
  completion and a real `DefaultEventLoop` cover the issue #205 schedule.
- `drainScheduledBeforeDone_keepsAgentDone` and `reset_clearsAllState`: terminal
  and explicit-reset behavior, without promising concurrent reset safety.
- The real-fork and rollback-without-new-blocks tests, and the client rollback
  accounting test.

I9 matters for interoperability: at the pinned ouroboros-network revision below,
`CanAwait` uses a 10-second short wait, whereas `MustReply` has a different
waiting policy. This supports promptly sending the per-request `AwaitReply`
when a final reply is unavailable. The reference is a repository revision,
not a verified cardano-node release-wide timeout guarantee.

### M2: Preserve coverage when later code changes touch this contract

No production refactor is planned. If later code or test work proceeds, its
entry gate is a concrete scope and PR destination under Q3. Its committed gate
is the existing focused tests passing unchanged, the full `./gradlew build`,
and explicit trace coverage for D3 and I9. No old agent implementation is copied
into the test tree. Optional differential comparisons use temporary, uncommitted
probes only; explicit expected traces remain the oracle.

D3's two additional traces start with two requests parked at tip and then
receive `FindIntersect`: one has already drained `AwaitReply`, the other still
has it queued. Assert discarded obligations, `Intersect` versus `CanAwait`
before draining, FIFO output, and no later final replies for the abandoned
requests. The author independently reproduced both with temporary probes;
the characterization tests are
`findIntersectWithParkedRequests_afterAwaitReplyDrains_discardsOldObligations`
and `findIntersectWithParkedRequests_beforeAwaitReplyDrains_preservesQueuedReply`.
I9 already has the committed `AW, RF, AW, RF, RF` trace; do not add a second test
that merely repeats it through a newly introduced resolver.

### M3: Downstream release QA

Downstream yaci-store and Yano campaigns are release QA rather than an automatic
merge gate for this contract; record the exact artifacts, commands, coverage,
and limitations, and define additional checks when observable behavior changes.

## Consequences, risks, and audit gates

The decision retains the current server-policy owner and adds an explicit
regression contract. It introduces no internal framework or runtime change.
The server's aggregate state selection intentionally remains separate from the
client's message-only transition table. A future extraction needs evidence of a
concrete benefit under D1a, not just matching the client class structure.

Risk classification for changes to this contract is **Medium**: a state/agency
mistake can stall synchronization or reorder replies on a healthy connection.
This section records the assessment without adding a new repository-wide ADR
header convention. This documentation revision changes no executable files.

Audit future diffs for reintroduced server `nextState()`/`sendRequest()` calls,
mutable fields on enum constants, changed listener timing, and changed wire or
public API behavior. Preserve the issue #205 tests and I9's trace. A future
implementation requires its own review; approvals of the code at `45d0728` or
this design do not certify such a change.

## Open questions and related work

- **Q1: Strict invalid-message handling.** Options are retaining permissive
  behavior or rejecting illegal `Done`/intersection sequences. D3 chooses
  compatibility now. Whether to add strict validation remains open for a
  separate change.
- **Q2: Generalize to other protocols — not planned.** There is no shared state
  API migration on this roadmap. Reopen only when another protocol has a
  demonstrated requirement; this ADR changes neither `Agent` nor `State`.
- **Q3: Destination of a future refactor — deferred; none is currently planned.**
  If D1a's concrete-need trigger is met, choose between a follow-up PR (preferred)
  and adding implementation to #206 while it is still open. The latter requires
  fresh review of that SHA and verification appropriate to that change. The
  maintainer must select the destination before such work starts. D4 already
  decides the ADR's own placement; Q3 does not reopen that decision.
- Coarse rollback-point selection, failed-write retry policy, and downstream
  crash-safe processing remain outside this decision.

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

Additional references verified for r3:

- [NodeServerSession: new chain-sync agent per connection](https://github.com/bloxbean/yaci/blob/45d0728ec9c39fb58354a0b0e1cb3dcc2de646ba/core/src/main/java/com/bloxbean/cardano/yaci/core/network/server/NodeServerSession.java)
- [Historical removal of the UTxO ADRs](https://github.com/bloxbean/yaci/commit/cdd7a79c9e4dcd096297ae0bbc4ae6edeecf08e0)
- [ouroboros-network chain-sync time limits, `stateToLimit`](https://github.com/IntersectMBO/ouroboros-network/blob/2fb69829a19b5c04e4fb212bdd78fb63f9d2d776/cardano-diffusion/protocols/lib/Cardano/Network/Protocol/ChainSync/Codec/TimeLimits.hs#L64)
- [ouroboros-network `shortWait` definition](https://github.com/IntersectMBO/ouroboros-network/blob/2fb69829a19b5c04e4fb212bdd78fb63f9d2d776/ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs#L111-L112)

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
- **r3 (2026-10-10; responds to reviewer3's review of
  `9e452c2dd9238d2b4670702125f830a5c19e15f3`):** R3-F1 selects Alternative 1
  under D1a and records extraction triggers; R3-F2 adds I9 and its existing
  trace/time-limit evidence; R3-F3 withdraws D3a and its concurrency tests;
  R3-F4 records the maintainer-requested placement and numbering in D4;
  R3-F5 scales M2/M3 to contract coverage and release QA; R3-F6 is superseded
  because no snapshot/adapter is proposed; R3-F7 numbers the contract rows,
  moves risk classification into Consequences, marks Q2 not planned, and adds
  the agency-only alternative. Prior decision and finding IDs are retained.
- **r4 (2026-10-10; follows reviewer3's design approval at
  `dec8fb05718878adae13dd5357fd3ac6df77661e`):** Promotes the two D3 traces to
  characterization tests under the maintainer's implementation instruction.
  R3-F8 corrects reference anchors; R3-F9 explains the retained filename,
  removes obsolete snapshot wording, and clarifies the non-intersect queue
  head; R3-F10 shortens M3; R3-F11 cites the confirmed `adr/` placement.
