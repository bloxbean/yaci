# ADR 0016: Leios on `next`: CCL parsing, complete blocks, activation by era

- Status: Proposed
- Date: 2026-11-19
- Risk: medium.
  - Blocks before Dijkstra must reach listeners exactly as on `next`: same objects, same thread, same error path.
  - With strict holding, a Dijkstra block that certifies an endorser block (EB) reaches `onBlock` with every ledger
    transaction, or not at all.
- Branch: `feat/leios-on-next` = `next` (with PR 244) + the Leios stack of PR 167 (167, 171, 221, 222, 223).
- Supersedes, of the Leios stack:
  - ADR 0008 (code review findings), ADR 0010 and ADR 0011 (Yaci-side serialization), and ADR 0013a
    (`0013a-leios-complete-blocks.md`, formerly `0013-leios-complete-blocks.md`) wherever they disagree with this ADR.
  - The Decision of ADR 0012. Its analysis still stands.
- Stands: ADR 0007 (the mini-protocols).

## Context

The Leios stack was written for `next-leios`, before PR 244.
- **Its own parsers** read Dijkstra and Leios CBOR in Yaci:
  - `DijkstraBlockBodySerializer`, `SubTransactionSerializer`, `DijkstraTransactionExtractor`;
  - `EndorserBlockSerializer`, `EndorserBlockTxListSerializer`, `LeiosVoteSerializer`;
  - `LeiosCborReader`, a CBOR cursor.
- **Its own models** for what it reads: `LeiosAnnouncement`, `model.leios.LeiosCertificate`, `RequiredTopLevelGuard`,
  `perasCertCbor`, `leiosCertified`.
- **Activation by network magic:** `LeiosConfig.Mode.AUTO` means magic 164, Musashi.

PR 244 moved block parsing to CCL's ledger-cbor reader (CCL ADR 0003).
- **What `next` already reads.** CCL `0.8.0-pre8` reads Dijkstra blocks, both header forms, sub-transactions, guards,
  Plutus V4, the Leios certificate and the EB announcement. Yaci maps them to `Block.leiosCertificate`,
  `Block.perasCertificate`, `HeaderBody.leiosCertInBody`, `HeaderBody.ebAnnouncement`, `SubTransaction` and
  `TopLevelGuard`.
- **What else CCL reads.** An EB's references (`RawEbReferences`, with the hash a header announces) and any
  transaction (`RawTx`).
- **CCL `master`** adds nothing to ledger-cbor over `0.8.0-pre8`:
  `git diff v0.8.0-pre8 origin/master -- ledger-cbor/src/main` is empty on 2026-11-19.

### Evidence: CCL and `next`'s mappers read the deployed format

I ran a probe at `8f4630d` (the PR 244 head) on the stack's Musashi `rc-2026w42` fixtures
(`core/src/test/resources/leios/w42`), which are bytes as the public relay served them:

| Input | Result |
|---|---|
| `block-9091`, a normal block, 354 txs | era Dijkstra, 354 bodies, `leiosCertInBody=false`, `ebAnnouncement` set |
| `block-10026`, certifying | 0 txs, `leiosCertInBody=true`, Leios certificate set, announces EB `f951f865…` (8,534 bytes) |
| `block-10027`, certifying | 0 txs, `leiosCertInBody=true`, `prevHash` = the hash of 10026 |
| `msg-leios-block-f951f865` | `RawEbReferences.of(item 1)` has 237 entries, and `hash()` = `f951f865…`, the announced hash |
| `msg-leios-block-txs-f951f865` | 237 `#6.24` byte strings, each `[body, witnesses, #6.259 aux, true]` (the Dijkstra block form, flag last); each one's blake2b-256 and length match its EB entry (237/237); body ids equal db-sync's (`dbsync-10027-tx-hashes.txt`, 237/237) |

Musashi was live on 2026-11-19: tip slot 2,739,695, block 121,143 (`adr/leios-evidence/musashi_tip.py`).

### Downstream use

None of the stack's API is on `next` or in a release.

On 2026-11-19, none of the following uses `onEndorserBlock`, `onLeiosVotes`, `LeiosDataListener`, `LeiosConfig`,
`LeiosNetworkClient` or the stack's Leios models:
- yano;
- yano-x;
- yaci-store's Leios branches (`develop/leios`, `feat/leios-musashi-w42`). They use only `Era.Dijkstra` and
  `PlutusScriptType.PlutusScriptV4`, which `next` has.

How each consumer reaches Yaci:
- yaci-store uses `BlockSync` and `BlockRangeSync` (`YaciStoreAutoConfiguration.java:78,85`). It calls `restart` once
  per range.
- Yano uses `PeerClient` (`DefaultPeerClientFactory.java:55`). Yano is a node, so it needs blocks as received.

## Decisions

### D1. CCL parses; Yaci maps

CCL `0.8.0-pre8`'s ledger-cbor views read all ledger CBOR: blocks, headers, transactions, EB references and EB
transactions. `next`'s serializers map them.

Removed:
- **Parsers:**
  - `DijkstraBlockBodySerializer`
  - `SubTransactionSerializer`
  - `util/DijkstraTransactionExtractor`
  - all of `serializers/leios/*`
- **Duplicate models:**
  - `RequiredTopLevelGuard`
  - `model/leios/*`, except `LeiosEndorserBlockRef`, which D3 moves
- **The stack's auto-merged edits** to `Witnesses`, `Redeemer` and `TxCborUtil` (`next`'s `TxCborUtil` already
  handles Dijkstra first, which made the stack's branch unreachable).
  - `next` reads Plutus V4 witnesses (key 8) and the `Guarding` redeemer tag on the CCL block path
    (`DataItemIsolation`).
  - The deprecated `Redeemer.deserialize(DataItem)` path stays as on `next`: no tag 6.

`next`'s model names are kept: `ebAnnouncement`, `leiosCertInBody`, `leiosCertificate`, `perasCertificate`.

Alternative rejected: keep Yaci readers for the EB and EB transactions. CCL reads both (see the evidence above). The
stack's EB reader also accepts omap dialects that no deployed network sends.

### D2. The mini-protocols stay; payloads are the bytes received

The `LeiosNotify` (id 18) and `LeiosFetch` (id 19) agents, states and message framing stay in core, as ADR 0007
decided. They are self-contained protocol code with their own tests, kept for Yaci users that speak Leios, Yano for
example.

**Payloads are kept as received, end to end.**
- **Today they are re-encoded twice.**
  - The mux decoder (`MiniProtoStreamingByteToMessageDecoder`) decodes each message with cbor-java and passes on
    `serialize(item)`. It then advances through the input by the re-encoded length. ADR 0008 recorded this.
  - The Leios states decode again and re-serialize payloads (`LeiosFetchStateBase.deserialize`,
    `LeiosCborUtil.toRawCbor`).
  - Both can change the bytes an EB hash is checked against.
- **After this change:**
  - For protocols 18 and 19 only, the decoder frames each message with CCL `CborSpan.at` over the accumulated
    original bytes, and passes that exact slice. An incomplete item waits for more bytes, as now.
  - The Leios message serializers slice payloads from it with `CborSpan`, and splice raw payloads into what they
    send. They never re-encode. `LeiosRawCbor` checks for exactly one item with `CborSpan`, not cbor-java.
  - A Leios stream that holds 64 MB with no complete item is malformed: the decoder throws, and the connection is
    closed. CCL cannot tell truncated input from malformed input.
  - Other protocols keep `next`'s decoder path unchanged (I1).
- **Votes are not parsed in Yaci.** CCL has no vote reader, and no consumer needs votes. `MsgLeiosVotes` carries the
  raw bytes.
- **Where the transaction list is split.** CCL has no reader for the LeiosFetch transaction-list message
  `[4, [* #6.24 bytes]]`, so the `MsgLeiosBlockTxs` serializer splits it into the byte strings inside each `#6.24`,
  using `CborSpan`.

Alternatives rejected:
- **Also remove `LeiosNotify` because nothing in this ADR uses it.** It is transport, not a parser; removing it would
  undo ADR 0007 for no gain in the sync path.
- **Fix the decoder for every protocol.** That is right in the long run, but it changes the path of every pre-Dijkstra
  message, which this ADR keeps as `next`'s. It is listed under related findings.

### D3. Complete blocks

This keeps sections 1 and 2 of the stack's ADR 0013a. For a block that certifies an EB, `onBlock` receives one block:
- **Transaction order:** the EB's transactions first, in EB order, then the block's own.
- **`Block`** is built with `certifyingBlock.toBuilder()`, so the header, both certificates and `Block.cbor` (the
  bytes of the block as received) are kept. Into it go:
  - the merged bodies and witnesses;
  - the aux data, with the block's own indexes shifted by the EB count;
  - the invalid indexes: the EB's own, then the block's own shifted;
  - `leiosEndorserBlock`.
- **The `transactions` list** is rebuilt from the merged block by `TransactionListBuilder`, as for any block.

`LeiosEndorserBlockRef` (EB hash, EB slot, EB size, and the count of leading transactions from the EB) moves to
`core.model`, next to `LeiosCertificate` and `EbAnnouncement`. It is left out of JSON when null.

### D3a. Mapping and checking EB transactions

A public core method, `BlockSerializer.withEndorserBlockTxs(Block, LeiosEndorserBlockRef, List<byte[]>)` (experimental),
maps and merges. It reuses the package's `TransactionBodySerializer.read`, `witnessSet` and `auxData`, so EB
transactions follow the same rules as the block's own: tolerant mappers, raw witness and aux CBOR when full CBOR is
returned, and the aux hash check.
- **Parts.** Each transaction's bytes are read as a span `[body, witnesses, aux / null, is_valid]`, the Dijkstra block
  form, the same parts `TxParts.ofTransactions` takes. Any other shape fails the attempt (D4a).
- **Validity** comes from `is_valid`.
- **Transaction id** = blake2b-256 of the body.
- **`txCbor`** keeps `next`'s contract for Dijkstra (`TxCborUtil`): the submission form `[body, witnesses, aux / null]`.

The resolver checks before mapping. Each failure is a failed attempt (D4a):
- **The EB:** the references' bytes as received hash (blake2b-256) to the parent's announced hash, and their length
  is the announced size. No hash repeats, and each hash is 32 bytes. An EB with no entries is valid (r11): after these checks the
  certifying block is delivered with `leiosEndorserBlock.txCount = 0` and no added transactions, and no transactions
  request is sent. CCL leaves these
  unchecked (`RawEbReferences`, ADR 0003 D5).
- **The transactions:** as many as there are entries, in entry order. Each transaction's bytes inside `#6.24` hash to
  its entry's hash, and its length equals the entry's size.

### D3b. Delivery: synchronous, a bounded budget in the listener call (r3, r5)

`BlockSync` and `BlockRangeSync` wrap their listener in the complete-block assembler. It forwards every callback at
once, on the calling thread, with nothing queued.

For a block with `leiosCertInBody == true`, it resolves the EB inside that same `onBlock` call, within a **budget of
45 s**:
- **One monotonic deadline.** Attempts (D4a) follow one another after a 1 s pause. Every wait (connect, handshake,
  request, pause, parent lookup) is clipped to the time left. Side-client cleanup runs off this call, so it never
  extends it.
- **Application code is not bounded.** `onEndorserBlockUnavailable` runs on this thread, so its duration adds to the
  call.
- **On success** the assembler forwards the complete block.
- **When the budget runs out**, it applies D3c.

The budget keeps the resolver's part of one call well under the peer's keep-alive limit of about 97 s. Application
callbacks made during the call are not bounded by it. The main connection's event loop is
blocked during the call.

For every other block, everything is `next`'s:
- the thread and the order;
- the error path: a listener exception from `onBlock` propagates to `BlockfetchAgent`, which reports it through
  `onParsingError` with the block's bytes. For a completed certifying block too, this is the ordinary path; it is not
  an EB failure.

The withdrawn r2 design (a holding queue on a worker thread) needed a lock between direct and held delivery, rules for
queued rollbacks, a full-queue policy and a reconnect from the delivered prefix. Delivering in the call removes them.

Its cost: a range sync waits for each certifying block's EB, one after another. Several slow successes in a row on a
buffered range can keep the main loop busy past the keep-alive limit. If the peer then drops the connection, that is
`next`'s ordinary disconnect. The Musashi run measures the wait (Verification 6). Overlap, for example starting the
fetch when ChainSync announces a certifying header, can be added later without changing the API.

### D3c. When the budget runs out: deliver as received, or halt (r5; the r3 drop rule and the r4 resync are withdrawn)

The assembler reports every failed attempt through `onEndorserBlockUnavailable`. The event carries its reason,
attempt number and `outcome`:
- `RETRYING` while budget is left;
- otherwise `DELIVERED_WITHOUT_EB_TXS` or `SYNC_HALTED`.

A listener exception from this callback is caught and logged, and changes nothing.

**When the budget runs out:**
- **`skipUnavailableEndorserBlocks = true`:** the block is forwarded as received, without its EB transactions
  (`DELIVERED_WITHOUT_EB_TXS`).
- **Otherwise (strict, the default), the sync halts** (`SYNC_HALTED`):
  1. The certifying block is not forwarded.
  2. The current start's listener delegate is deactivated. Every later callback of that start (blocks, headers,
     rollbacks, `batchDone`, disconnects) is dropped.
  3. The call returns. A short-lived thread then stops the sync's main client, as `stop()` does. It does not stop a
     newer start (see below).
  4. `isRunning()` is false from then on.
- **The application restarts** from its own cursor, as it does after any failed sync, with
  `startSync`/`restart`/`fetch`. That start gets a new delegate and a new budget, and the certifying block comes again.
  The announcement cache (D4b) belongs to the `BlockSync` or `BlockRangeSync` instance, so it carries over.

Halting keeps Yaci's contract with no recovery machine inside it. Yaci never skips a block, and never delivers a
certifying block without its EB's transactions unless asked to. Recovery never uses a position of the halted start. The application owns resumption, as it already does after `intersactNotFound` or a stopped connection.
- **In yaci-store**, a halted sync is a stopped sync. Its auto-recovery
  (`store.admin.auto-recovery-enabled`, off by default) restarts it. A one-line follow-up can also publish
  `RequiredSyncRestartEvent` on `SYNC_HALTED`.

The r4 design restarted the main connection inside Yaci, from the last delivered point. It needed:
- an owner for the replacement client;
- a bounded replacement startup;
- generation fences shared by adapters;
- retry state across replacements;
- intersection fallbacks;
- range completion rules;
- a pinned callback trace.

(Codex F33 to F39.) Halting needs none of these.

**Ownership.** These rules are needed because the halted call runs inside the synchronized
`BlockfetchAgent.receiveResponse`, and `Session.dispose` needs that monitor and the event loop.

1. **Exactly one terminal outcome per call:** `DELIVERED_WITHOUT_EB_TXS` or `SYNC_HALTED`, even when the budget ends
   during a pause. On a halt Yaci also logs at ERROR the certifying block's slot, hash and number, the EB hash, the
   reason and the outcome.
2. **Halt order:** mark the start halted, so `isRunning()` is already false (r11), then report `SYNC_HALTED` (on
   the calling thread), then deactivate the delegate, then return.
   - The halting thread may be started before the return.
   - Its first step, disposing the session, waits for the agent monitor that the returning call holds. So the
     shutdown happens only after the call has unwound.
3. **`isRunning()`** is false from the moment of the halt. It reads the start's halted flag, not only the client.
4. **The helper lock** only selects, retires and publishes a start. It covers:
   - `startSync`, `startSyncFromTip` and `stop` of `BlockSync`;
   - `start`, `restart` and `stop` of `BlockRangeSync` (`fetch` takes no lock, r12: it is `next`'s, see rule 9);
   - the halting thread.
5. **Nothing on the event-loop path takes the helper lock.** The halt decision and delegate deactivation use
   `volatile` state.
6. **The lock is released before** any blocking startup, shutdown or application call. The client is captured under
   the lock and acted on outside it.
7. **The halting thread** shuts down the captured client only, and only if its start is still the current one.
8. **Retirement is fenced in the helper; `NodeClient` stays `next`'s (r10).**
   - Each start owns fresh clients. Retiring a start shuts its clients down. What its delegate still forwards is
     rule 9: a replaced start forwards only disconnects, a stopped start forwards as on `next`.
   - Shutting a client down holds no lock (r12). A caller that finds a shutdown of that client in progress returns
     at once; the running one does the work, and a failure is logged, never thrown into a new start.
   - A daemon re-check then shuts down again any retired client that is running again, at +5 s and +30 s, and every
     30 s after that while that client's own `start()` has not returned (r12). This covers `next`'s reconnect-gap
     revival, a `start()` that ran after retirement, and one stuck in `next`'s connect-retry loop. A zombie can
     therefore cost a connection for a while, never a callback to a newer start.
   - (r7–r9 changed `NodeClient` to generations and a permanent `retire()`. The implementation review found races in
     that core path, and it touched every Yaci user, so it is withdrawn.)
8a. **A replacing operation** (`startSync`, `startSyncFromTip`, `start`, `restart`) always shuts down the client it
    displaces, even when `isRunning()` is false.
9. **Cancellation:** every operation in rule 4 except `fetch` cancels an attempt in progress.
   - A per-call state decides, by compare-and-set, between cancellation and the commitment to report or deliver.
   - A plain `stop()` without an attempt in flight deactivates nothing. The stopped start's `onDisconnect` and
     other callbacks reach the listener as on `next` (I1).
   - A start replaced by a newer start of the same helper forwards nothing more but disconnects (r12: every
     `onDisconnect`, so both adapters in tip sync, as on `next`; r11's "once" is withdrawn). A replaced client that
     revives through `next`'s reconnect path cannot deliver blocks to the listener. With inlining off, replacement
     is `next`'s. Only a cancelled certifying call deactivates its
     start, when it returns, so the certifying block's buffered successors are dropped.
   - An interrupt during an attempt counts as the budget running out.
   - The woken call returns without delivering, without reporting, and without a halting thread.
   - If skip-mode delivery was already decided (`DELIVERED_WITHOUT_EB_TXS` reported), that one `onBlock` call
     finishes first.
   - `fetch` cancels nothing and is `next`'s `fetch` (r11), including a `fetch` from inside `batchDone`. With
     inlining on it also fails, before changing anything, after a halt or while a certifying call is in flight on
     that start (until the call commits; delivery itself does not block `fetch`, r12). The in-flight call is read
     before the halt flag, which is published before the call is cleared. Neither can happen before Dijkstra. (The r9/r10 idle-range gate is withdrawn: it refused a `fetch`
     from `batchDone`, which works on `next`, and a `fetch` racing `BlockfetchAgent`'s cursor reset could leave it
     closed for good.)
9a. **Start fencing (r10):**
    - A start checks that it is still current right before `client.start()`.
    - After startup returns, it checks again under the lock. A start retired in the meantime has its client shut
      down.
    - Rule 8's re-check covers the window between the two checks.
10. **Restarting from inside `onEndorserBlockUnavailable`** must hand off to another thread. `stop()` on the event loop
    waits on its own loop, as `stop()` from `onBlock` does on `next`.

A halted start's internal cursors (`confirmBlock`, BlockFetch's `from`) are never reused: its client is discarded.
- **Range resumption** is `restart(listener)` followed by `fetch(from, to)`.
- **A halted `BlockRangeSync`** sends no `batchDone`.

### D4. Activation by era, not by network magic

- **The main connection** is exactly as on `next`: ChainSync, BlockFetch and KeepAlive, with the caller's handshake
  and version table. It carries no Leios agents.
- **Activation:**
  - A block activates assembly when `leiosCertInBody == true` (D3b). Only a 12-item Dijkstra header carries it.
  - The assembler remembers each block's `ebAnnouncement` (hash, size) and slot by block hash, 4,096 entries, so a
    certifying block finds its parent's announcement through `prevHash`.
- **The side connections** (D4a, D4b) are opened only when a certifying block needs them.

`Constants.MUSASHI_PROTOCOL_MAGIC`, `LeiosConfig.Mode` and `LeiosNetworkClient` are removed. So the same Yaci syncs
mainnet, preprod and preview as `next` does. It completes certifying blocks on any network that has reached Dijkstra,
Musashi today, with no network list.

Node-to-client needs nothing in Yaci: the node inlines the EB's transactions into the certifying block it serves
(stack ADR 0013a, section 5). `N2CChainSyncFetcher` keeps its warning for a certifying block that arrives with no
transactions, using `getLeiosCertInBody()`.

### D4a. The LeiosFetch side connection

The resolver owns the side connection: a `TCPNodeClient` with its own event loop, carrying the handshake and
`LeiosFetchAgent` only.
- **Its version table** is `N2NVersionTableConstant.v11AndAbove(magic)`, whatever the caller's main table is.
- **The magic** is the one in the main connection's negotiated `AcceptVersion`.
- **Accepted** only when all of these hold:
  - the accepted version is one this table offered;
  - it is V15 or later;
  - it is not app-layer;
  - its magic is the main connection's.

**Ownership (r10).** Each start has its own side connection, so retiring or halting a start closes only its own.
**Reuse.** The connection is reused across attempts of the same start.
- At the start of an attempt it is replaced when it is older than 45 s or was idle for 30 s. A connection's age plus
  an attempt's deadline (at most 45 s) stays under the peer's keep-alive limit, so no timer and no periodic KeepAlive
  are needed.
- An idle connection that the peer closes is simply replaced at the next attempt.

**One attempt** = connect if needed, handshake, parent lookup if needed (D4b), the EB request and the transactions
request.
- **Its own result object.** Callbacks of an earlier client (a late accept, disconnect, EB or transaction list) are
  ignored. The `disconnected()` that `Session.start()` raises while connecting is not a loss.
- **A deadline,** the time left in D3b's budget, starts before connecting and covers everything. The handshake wait
  is capped at 10 s, so a stuck handshake leaves budget for a retry.
- **Reply cap (r11).** Each wait for the EB or the transactions reply is capped at min(time left, 15 s)
  (`REPLY_CAP_MILLIS`). When the cap expires, the side connection is closed and the attempt fails, so the next attempt
  (after RETRYING and the pause) uses a fresh connection. Found live on Musashi: a single silent reply used the whole
  45 s budget at `attempt=1`, and the same EB was served at once after a restart.
  - The parent lookup's FindIntersect and block waits use the same cap (r12).
  - (r13) For the EB and the transactions reply, the cap bounds silence, not the whole reply (`STALL_MILLIS`): the
    attempt fails when nothing has arrived on LeiosFetch for 15 s, and a reply that keeps arriving may use the rest of
    the budget. The side connection's decoder records the time of each LeiosFetch mux segment on its
    `LeiosFetchAgent` (`segmentReceived`), before the message is complete; the main connection has no
    `LeiosFetchAgent`, so nothing changes there. The floor drops from about 1 MB/s to about 0.35 MB/s for a 15 MB
    reply (it must finish within the 45 s budget). The budget itself stays: the call runs on the main connection's
    event loop, which must answer the peer's KeepAlive within about 97 s (D3b).
  - Only non-empty segments from the responder count. A segment counts once whole (at most 64 KiB), so a link under
    about 4 KiB/s still fails as before. A peer that trickles bytes can hold an attempt until the 45 s budget; the
    outcome is the same RETRYING / halt as a stuck reply, only later.
  - The parent lookup's replies (one block or one intersection each) keep the whole-reply cap (`REPLY_CAP_MILLIS`).
- **Failing fast.** Each side connection has a terminal state. Every wait (handshake, EB, transactions, each parent
  window) ends at once when the connection is lost.
  - At the deadline, the attempt's clients are shut down.
  - A connect that completes after that closes its own channel.
- **Session change.** `Session` gets a small change: its handshake wait also ends when the session is disposed and reconnection is off (a deliberate end).
  A session the peer closed is disposed on the way to a reconnect, and keeps waiting as on `next`. Today
  it polls until the handshake is done, so a shut-down client's start thread would wait forever.
- **Connect settings:** `autoReconnect` off.

**An attempt fails** on any of these:
- the connection is refused or lost;
- the handshake is refused, or its version or magic is wrong;
- the peer answers with an error, closes, or stays silent;
- the parent is not found;
- D3a's checks fail;
- the deadline passes.

On failure the attempt's client is closed. A failure that repeats the same way is logged once per EB.

### D4b. The parent of a certifying block

The EB point is `[slot of the announcing block, EB hash]`, so a certifying block needs its parent's announcement and
slot.
1. **The announcement cache** first. It belongs to the `BlockSync` or `BlockRangeSync` instance and lives across
   `restart`, `startSync` and halts. A range that starts on a certifying block finds the last block of the previous range.
   That is yaci-store's pattern.
2. **Otherwise a short-lived side connection** (`ParentBlockLookup`: ChainSync and BlockFetch) fetches the parent
   block privately, within the attempt's deadline. It never emits the parent to the listener, and it checks that the
   block it gets has the hash `prevHash`.
   - **The parent's point is known** (tip sync's start point has hash `prevHash`): it fetches that point directly.
   - **Otherwise it searches.** It sends `FindIntersect` with 1,000 candidate points `(slot, prevHash)` per window.
     Each attempt starts at the child's window and goes back window by window until it finds the parent or its
     deadline passes (r6: no position is kept across attempts, so a peer that lagged and then caught up is searched
     again from the start).
     - At one round trip per window, an attempt covers on the order of 100,000 slots.
     - Musashi has a block about every 20 slots, so the first window almost always holds the parent.
     - A parent beyond one deadline's reach is never found by search. That needs a slot gap of the order of a day.
       The recovery is the application's: a restart from an earlier point delivers the parent first, and the cache
       then has it.

### D5. One small configuration object, on the two sync helpers

`LeiosConfig` keeps two settings. It is an optional constructor argument of `BlockSync` and `BlockRangeSync` only:

| Setting | Default | Meaning |
|---|---|---|
| `inlineEndorserBlockTxs` | `true` | `false`: certifying blocks are delivered as received, and no side connection is opened |
| `skipUnavailableEndorserBlocks` | `false` | `true`: when the 45 s budget runs out, deliver the block without the EB transactions, and report the gap; `false`: halt the sync (D3c) |

Removed:
- the global `YaciConfig.inlineEndorserBlockTxs`;
- `fetchTxs`, `maxTxsPerEndorserBlock`, `txsOfferWaitMillis` and `deliverVotes` (D6);
- `LeiosConfig` on `N2NChainSyncFetcher` and `BlockFetcher`.

**Scope.** Complete blocks come through `BlockChainDataListener.onBlock` of N2N `BlockSync` and `BlockRangeSync`.
These deliver blocks as received, as on `next`, and this is now documented on them:
- `N2NChainSyncFetcher.start(Consumer<Block>)`;
- `BlockFetcher`;
- `BlockStreamer`;
- `PeerClient` (Yano), out of scope.

### D6. The raw Leios event API is removed

Removed:
- `BlockChainDataListener.onEndorserBlock` and `onLeiosVotes`;
- `LeiosDataListener`;
- `LeiosSyncCoordinator` (LeiosNotify and LeiosFetch on the main connection);
- `LeiosEndorserBlockFetcher`;
- `LeiosNetworkClient` (D4a replaces its use);
- `EndorserBlockClosure`, `EndorserBlockEvent` and `LeiosVotesEvent`.

They fed raw Leios data to applications that do not exist (see "Downstream use"). They are also the only reason the
main connection needed Leios agents.

Kept: `BlockChainDataListener.onEndorserBlockUnavailable`, a default method, and `EndorserBlockUnavailableEvent`.

Alternative rejected: keep them behind an opt-in. That keeps about 1,000 lines and a second code path on the main
connection for no known user. Adding them back later is additive.

### D7. Records

This ADR is 0016:
- `next` has ADRs 0013 to 0015, and the stack's `0013-leios-complete-blocks.md` collides with `next`'s 0013, so that
  file is renamed `0013a-leios-complete-blocks.md`.
- Each of the stack's ADRs listed under "Supersedes" gets a status line pointing here.
- `docs/leios/` is updated to D1 to D6. Text about removed APIs is removed.

## Invariants

- **I1 (D2, D3b, D4).** For every block without `leiosCertInBody == true`, of a start that has not halted:
  - each `BlockChainDataListener` callback of `BlockSync` and `BlockRangeSync` receives the same arguments as on
    `next`, on the same thread, with the same exception path;
  - the main connection's version table, agents and the mux decoding of its protocols are `next`'s.
- **I2 (D3, D3a).** For a certifying block delivered with inlining on, unless `onEndorserBlockUnavailable` reported
  `DELIVERED_WITHOUT_EB_TXS` for it:
  - its first `leiosEndorserBlock.txCount` transactions are the certified EB's, in EB order;
  - each one's bytes as received hash to its entry's hash and have its entry's length;
  - the EB's references, as received, hash to the parent's announcement and have its size.
- **I3 (D3b, D3c).** The listener receives callbacks in the order its connection received them. After `SYNC_HALTED`,
  it receives nothing more from that start; the halted certifying block is not among what it received.
- **I4 (D1, D2).** No class in Yaci decodes ledger CBOR that CCL reads. Leios payloads in core are slices of the bytes
  received, never re-encoded.
- **I5 (D3, D5).** Against `origin/next`, the public API of `core` and `helper` only gains members. JSON of a block
  without `leiosEndorserBlock` is byte-identical, a Dijkstra block included.

## Compatibility

Against `next` the change is additive:
- `Block.leiosEndorserBlock`: nullable, absent from JSON when null. `Block`'s 7-argument public constructor passes
  null for it.
- `BlockChainDataListener.onEndorserBlockUnavailable`: a default method.
- New `BlockSync` and `BlockRangeSync` constructors that take a `LeiosConfig`.
- `BlockSerializer.withEndorserBlockTxs`.
- `helper.listener.TransactionListBuilder`, which builds the `onBlock` transaction list, moved out of
  `BlockFetchAgentListenerAdapter` so the assembler can rebuild it.
- The payload-bearing `deserializeDI(DataItem)` of the four Leios payload messages throws: a decoded tree has lost
  its bytes. Use `deserialize(byte[])`. These classes were never released.
- The core Leios agents.

A start that a newer `startSync`/`start`/`restart` or a `stop` supersedes while it is still connecting no longer
throws its startup failure to its caller; the failure is logged at debug, and the newer start proceeds (r11).

Behaviour change, intentional: on a Dijkstra network with Leios, a certifying block's callback carries the EB's
transactions, and its delivery can wait.

Against the unmerged stack, D1, D5 and D6 remove API that was never released.

## Verification

1. **Unit tests on the w42 fixtures:**
   - blocks 9091, 10026, 10027 and 10428;
   - `withEndorserBlockTxs` on block 10027 with EB `f951f865` and its 237 transactions gives db-sync's ids, in order;
   - `is_valid = false` gives index 0 in `invalidTransactions`, with full CBOR on;
   - a mixed case with own transactions, aux data and invalid indexes on both sides;
   - a JSON check: exact JSON without the field, and the field when set.
2. **Mux and codec tests**, through the installed decoder:
   - a byte-distinct but valid EB keeps its exact bytes;
   - a fragmented Leios message interleaved with a legacy protocol message, in both mux directions;
   - a complete Leios message followed by a partial one in one segment;
   - the legacy trailing-item behaviour unchanged;
   - a malformed Leios stream closing the connection;
   - an announcement and votes keeping their bytes on receive and re-send.
3. **Resolver tests** over a local fake N2N peer (a socket server in the test):
   - D3a: a wrong length, a wrong hash, an empty EB and a duplicate entry are rejected;
   - the handshake:
     - a refusal;
     - an unoffered or app-layer version;
     - a wrong magic;
     - a peer silent before the handshake: the deadline fires, the start thread ends, and the client is not left
       running;
     - a connect that completes just after the deadline closes itself;
   - fetching:
     - a peer silent after the request, or closing without a reply: the attempt fails, and the next attempt's request
       is on the wire;
     - a late reply or disconnect from a closed client: ignored;
   - parent lookup:
     - a known point;
     - a hit in a later window;
     - a wrong returned block;
     - the parent never reaching the listener;
   - connection rotation by age and by idle time.
4. **Assembler tests** with fake fetchers:
   - thread identity and exception propagation (I1);
   - a listener exception on a completed certifying block takes the ordinary path;
   - a throwing `onEndorserBlockUnavailable` changes nothing;
   - a success on the second attempt delivers the complete block once, after one `RETRYING`;
   - a strict budget exhausted:
     - `SYNC_HALTED`;
     - the block is not forwarded;
     - later callbacks of that start are dropped;
     - the main client is stopped;
     - `isRunning()` is false;
   - a restart after a halt delivers the complete block, with the cache warm;
   - a halting thread that runs after a newer `startSync` does not stop it;
   - skip mode with the budget exhausted: `DELIVERED_WITHOUT_EB_TXS`, and the block is delivered once, as received,
     with its own transaction list;
   - the budget ending during a pause gives exactly one terminal event;
   - both installed adapters driven through a halt; after it, all of these are dropped, and a fresh start forwards
     them again:
     - both disconnects;
     - intersect found and not found;
     - rollback;
     - both header forms;
     - `noBlockFound`;
     - parse errors;
     - a queued `batchDone`;
   - `NodeClient.shutdown()` during an automatic reconnect's no-session window: the client stays down;
   - a halting thread finishing after a newer start, with the old loop still inside `receiveResponse`: the new client
     keeps running;
   - `start → shutdown → start` on one `NodeClient`, with the old session's reconnect delayed: the new session is
     unaffected;
   - a replacing start while `isRunning()` is false: the old transport stays down;
   - cancellation against skip-mode delivery, both orders, with a buffered successor block;
   - the side client and its event loop are closed on `stop()` with no attempt in flight;
   - `fetch` on a halted range, and a second `fetch` while a range is held on a certifier with its successor and
     `BatchDone` buffered, both fail before changing anything;
   - a start retired after its last helper check and before `client.start()` runs: the old client makes no
     connection attempt;
   - `stop()` during an attempt and during a pause returns within 1 s, from another thread;
   - a range starting on a certifier, with a warm and a cold cache;
   - a reflection test that the assembler forwards every `BlockChainDataListener` method.
5. **API:**
   - `PublicApiCompatibilityTest` and `ModelJsonPinTest` unchanged;
   - a signature listing of the `core` and `helper` jars against `origin/next`. `adr/leios-evidence/apicompare.py` is
     fixed to fail on a tool error, a missing class or a changed member.
6. **Musashi, live, over the unchanged `v4AndAbove` main connection:**
   - range sync from the first block to the tip;
   - tip sync for at least 30 minutes;
   - per certifying block, as many leading transactions as its EB has entries;
   - the wait per certifying block, the count of halts and restarts, and the longest gap between the main
     connection's KeepAlive responses;
   - no parse error and no gap.

   This assumes the relay serves old certified EBs. A baseline range sync with `origin/next` is recorded.
7. **Preview and preprod:**
   - the `yaci-sync-check` full range sync, with 0 parsing errors and 0 gaps;
   - a per-block digest comparison of `Block` JSON and transaction lists between `origin/next` and this branch over
     the same ranges (I1).

   Mainnet: a bounded range with the digest comparison. A full sync if time allows, otherwise recorded as skipped.

## Consequences

- There is one parser and one model for each structure, and the merged branch is smaller than the stack.
- Leios works on any network that reaches Dijkstra. Nothing is network-specific.
- Applications lose the raw EB and vote callbacks, which no one uses. The core agents remain for that.
- A change to the Leios ledger encoding is a CCL change first.
- **Delivery of a certifying block waits for its EB** on the listener's thread, for at most 45 s of resolver time plus
  the application's own callbacks. A range sync of a
  Leios chain is slower by about one EB round trip per certifying block.
- **In strict mode, an EB that cannot be had within 45 s halts the sync visibly** (`SYNC_HALTED`, then
  `isRunning() == false`), and the application restarts it, as after any failed sync. For yaci-store this means
  enabling auto-recovery, or the one-line follow-up in D3c. Without either, the sync stays stopped until someone
  restarts it.

  **Before a release is used on a Leios network,** yaci-store must handle `SYNC_HALTED`. The follow-up publishes
  `RequiredSyncRestartEvent` from another thread, with a growing backoff between restarts.
  - Its auto-recovery and `RequiredRestartProcessor` can skip a restart (disabled, locked, debounced or
    rate-limited), so that follow-up is what makes recovery unconditional.
  - **The restart must deliver the halted certifier again, if it is still on the peer's chain.** If the peer rolled it
    back during the backoff, recovery is from a surviving committed cursor (yaci-store's cursor walk). An empty store
    whose configured start point is gone reports it to the operator and does not start.
  - **Its replay mechanics:** In range mode, yaci-store's `from` is inclusive. In tip
    mode, it must intersect at a committed predecessor, never at a point it saved but did not process. `StartService`
    saves its configured `from` before fetching, so an empty store that starts on the halted certifier itself must be
    restarted from that `from`, inclusively. The follow-up tests this case.
  - **A halt stays pending** until a restart is accepted or an operator stops the sync. A restart request the processor
    rejects is retried with the backoff.
  - **Before restarting, after the backoff,** the follow-up checks that the same halt is still pending and recovery is
    still wanted. That way it never reverses an operator's start or stop made in the meantime. The halt's identity
    goes through to the final start, and any operator action cancels it.

## Open questions

- **Q1.** Keep `inlineEndorserBlockTxs = false`? Lean: keep. It costs one branch, and gives raw-block consumers of the
  sync helpers the blocks as received.
- **Q2.** Vote reading in CCL? It is not needed for complete blocks. Lean: only when a consumer asks.
- **Q3.** Should yaci-store restart on `SYNC_HALTED` by itself (the one-line follow-up), even with auto-recovery off?
  Lean: yes, in the yaci-store change that adopts this Yaci.

## Related findings (out of scope)

- Over node-to-client, a certifying block whose EB the node could not read arrives without transactions. Yaci cannot
  fetch EBs over node-to-client.
- The mux decoder re-encodes the messages of every other mini-protocol and advances by the re-encoded length (D2).
  Fixing it for all protocols is a separate change, with its own pre-Dijkstra verification.
- `next`'s own reconnect paths can deliver a block twice:
  - `BlockfetchAgent` re-requests `[from, to]` with `from` = the last delivered block;
  - tip sync confirms a block (`confirmBlock`, then `RequestNext`) once the listener returns. Listeners are called in
    reverse order of registration.

  At-least-once delivery is `next`'s contract, and is unchanged here.
- `next`'s reconnect runs `NodeClient.start()`, and so the blocking `Session.handshake()` wait, on a Netty event-loop
  thread. A peer that keeps closing connections during the handshake can occupy every loop thread of an
  auto-reconnecting client. Pre-existing on `next`; seen in tests only through a port collision (since fixed).
- `next`'s main connection has no handshake timeout. A relay that accepts TCP but never answers the handshake blocks
  `BlockSync.startSync` / `BlockRangeSync.start` for ever; `stop()` from another thread ends it (with this ADR's
  `Session` change). Seen live on Musashi (2026-11-19, one relay address of `leios-node.play.dev.cardano.org`).
  Applications that restart a sync should bound the start. Pre-existing on `next`.

## Revision history

- r1 (2026-11-19): initial version.
- r2 (2026-11-19, responds to the reviews of `aa612dec` by Fable, Opus and Codex):
  - The assembler is direct until something is held (D3b, I1), and rollback is specified (D3c).
  - The side connection is specified: its own table, the accept rule, its failures, bounded connect, and a new client
    per attempt (D4a).
  - Parent lookup, its windows and the cache across `restart` are specified (D4b).
  - EB checks are specified, including lengths, empty and duplicate entries, and the size (D3a).
  - Validity comes from `is_valid`, and `txCbor` keeps `next`'s contract (D3a).
  - The merge moves to a public core method built on `toBuilder` (D3a).
  - Payloads are sliced, not re-encoded (D2).
  - The scope of entry points is stated, and `LeiosConfig` is dropped from `N2NChainSyncFetcher` and `BlockFetcher`
    (D5).
  - `LeiosNetworkClient` is removed (D6).
  - Verification adds transport, rollback, range-boundary and helper API checks, and an I1 digest comparison.
  - The Supersedes line and the ADR 0012 wording are fixed.
  - `LeiosNotify` is kept, with its reason stated (D2, against the suggestion to remove it).
- r3 (2026-11-19, responds to round 2 of `1b2f9246`: Fable and Opus approved with P2/P3, Codex requested changes):
  - The holding queue is withdrawn. Delivery is synchronous in the listener call (D3b), which resolves Codex F13, F14,
    F17, F18 and F20 and Fable/Opus F10/F11 by construction.
  - New rule: a certifying block off the peer's chain is dropped, and the rollback follows (D3c).
  - The mux decoder frames protocols 18 and 19 from the original bytes (D2, Codex F12).
  - One deadline per attempt, covering connect and handshake. Per-attempt state, late callbacks fenced, connect
    settings corrected (D4a; Codex F15, F16; Fable F12; Opus F13).
  - The magic comes from the negotiated `AcceptVersion`, and the accepted version must be one offered (D4a, Codex F21).
  - The side connection is reused and closed after 30 s idle, with no KeepAlive needed (D4a, Codex F23).
  - The parent is fetched directly at a known point, and the search continues to slot 0 across attempts (D4b; Opus
    F12, Codex F19).
  - Attempt failures are reported from the blocked call (D3b, Codex F18).
  - Verification adds pipeline, deadline, magic and version, D3c, an exact Dijkstra JSON check, a fixed
    `apicompare.py`, the per-certifier wait, a Musashi baseline and a bounded mainnet run (Codex F10, F22; Fable F13,
    F14).
- r4 (2026-11-19, responds to round 3 of `bc67aea7`: Fable approved with P2/P3; Opus and Codex requested changes):
  - The r3 drop rule is withdrawn. A failed attempt in strict mode fences the connection and resyncs from the last
    delivered point, with backoff (D3c). This covers Codex F24 to F27, Opus F15 and F16, and Fable F15 and F16.
  - One bounded attempt per call, 45 s, under the keep-alive limit (D3b, Codex F24, Fable F17).
  - Cancellation order on stop, restart and start, and a `Session` handshake wait that ends on dispose (D3c, D4a;
    Codex F28, Opus F17).
  - The side connection is rotated by age and idle time, with no timer (D4a; Codex F29, Fable F18).
  - Malformed-stream bound, raw splicing on send, and `LeiosRawCbor` checked with CCL (D2; Codex F30, F31).
  - The event `outcome`, and exceptions from the unavailable callback isolated (D3c, Codex F32).
  - I3 restated. Verification updated.
- r5 (2026-11-19, responds to round 4 of `75695477`: Fable and Opus approved with P2/P3; Codex requested changes):
  - The r4 in-library resync is withdrawn. When the budget runs out in strict mode, the sync halts, and the application
    restarts from its own cursor (D3c). This removes Codex F33 to F38 and Opus F20, F21, F23 and F24 by construction.
  - The 45 s budget covers several attempts within one call (D3b).
  - The event `outcome` is `RETRYING`, `DELIVERED_WITHOUT_EB_TXS` or `SYNC_HALTED` (D3c, Codex F32 follow-up).
  - Ownership: one lock, `volatile` fields, and the halting thread never stops a newer start (D3c; Opus F22, Codex
    F35).
  - The side connection is rotated at 45 s of age, and a late connect closes itself (D4a; Fable F21, Codex F29, F40,
    F28).
  - A listener exception on a completed certifier takes the ordinary path (D3b, Codex F41).
  - I3 restated. Verification 4 updated. Consequences and Q3 cover the downstream effect.
- r6 (2026-11-19, responds to round 5 of `cef7be2f`: Fable and Opus approved with P2/P3; Codex requested changes):
  - Ownership is specified (D3c), with one terminal outcome, the halt order, the lock discipline, a durable
    `NodeClient.shutdown`, all entry points, and silent cancellation (Codex F17, F42, F43, F44, F47; Opus F25, F26).
  - One monotonic deadline per call (D3b, Codex F47).
  - The parent search keeps no position across attempts (D4b, Codex F49).
  - I1 is scoped to starts that have not halted (Codex F48).
  - The yaci-store follow-up is a release prerequisite for Leios networks, with backoff, the auto-recovery conditions,
    and the cursor case (Consequences; Fable F22, F23; Codex F45, F46).
  - The listener order is fixed in Related findings (Codex F17).
  - `apicompare.py` checks the coverage of both listings (Codex F50).
- r7 (2026-11-19, responds to round 6 of `f71cb05d`, Codex):
  - Shutdown is durable per session generation, so a deliberate restart of one client still works (Codex F51).
  - A replacing start always shuts down the displaced client (Codex F43).
  - Cancellation covers every entry point, deactivates before it wakes, and a bare `fetch` on a halted range fails
    (Codex F44, F52).
  - The yaci-store follow-up must replay the halted certifier and recheck before restarting (Codex F45, F46, F53).
  - The parent search's reach is documented, with its recovery (Codex F54).
  - The promise is reworded: recovery never uses a halted start's positions (Codex F17).
  - Application callback time is not bounded (Codex F47).
  - The side client is closed on stop (Codex F55).
  - The byte-array codecs no longer re-encode (Codex F56, done in core).
  - Verification adds the KeepAlive gap (Codex F40).
- r8 (2026-11-19, responds to round 7 of `761b6011`, Codex):
  - `fetch` cancels nothing, and is valid only on an idle range (Codex F57).
  - Start fencing (Codex F58).
  - Replay only of a certifier still on the chain (Codex F60).
  - A pending halt survives a rejected restart, and its identity goes through to the final start (Codex F46, F53).
  - The time bound covers the resolver only (Codex F47).
  - The vote list keeps its encoding, and codec errors keep their cause (Codex F61, F62, done in core).
- r9 (2026-11-19, responds to round 8 of `60c869fa`, Codex): start fencing by a permanent per-client `retire()`,
  ordered with `start()`, so a retired start can open no connection (Codex F58). The vote message derives its votes from
  the one list it re-sends (Codex implementation note, done in core).
- r10 (2026-11-19, responds to the implementation review of `a6ca7c1b` by Fable, Opus and Codex):
  - `NodeClient` generations and `retire()` are withdrawn. Fencing is done in the helper, with a re-check, and core
    networking stays `next`'s (rules 8, 9a).
  - Each start has its own side connection (D4a).
  - A per-call compare-and-set state, and an interrupt counted as the budget running out (rule 9).
  - A stop without an attempt in flight forwards `onDisconnect` as on `next` (rule 9, I1).
  - The halting thread's timing is stated (rule 2).
  - Fail-fast waits and the 10 s handshake cap (D4a).
  - The `fetch` gate applies only with inlining on (rule 9).
  - The Compatibility section lists `TransactionListBuilder` and the payload `deserializeDI`.
- r13 (2026-11-19, responds to the maintainer's question on raising the timeout):
  - The EB and transactions waits fail on 15 s of silence instead of 15 s per whole reply; LeiosFetch segments
    received mark progress (D4a). The 45 s budget is unchanged (D3b).
- r12 (2026-11-19, responds to implementation review round 3 of `90f5bc78` by Fable, Opus and Codex):
  - Client shutdown holds no lock; the r11 monitor could deadlock a stop from a listener callback (rule 8; all three).
  - Re-checks continue while a retired client's `start()` has not returned (rule 8; Codex).
  - A replaced start forwards every `onDisconnect`, as on `next` (rule 9; Opus).
  - `fetch` admission order, and `fetch` allowed during delivery (rule 9; Codex, Opus). Rule 4 no longer lists `fetch`
    under the helper lock (Fable).
  - The parent lookup uses the reply cap; the cap's throughput floor is stated (D4a; Opus, Fable).
  - `LeiosFetchAgent.shutdown()` also fails a request already written (core; all three).
- r11 (2026-11-19, responds to implementation review round 2 of `bc7800a3` by Fable, Opus and Codex, and to the live
  Musashi range run of `a6ca7c1b`):
  - The idle-range `fetch` gate is withdrawn; `fetch` is `next`'s, refused only after a halt or during a certifying
    call (rule 9; all three reviewers, P1).
  - A replaced start forwards nothing but one `onDisconnect` (rule 9; Codex).
  - Each reply wait is capped at 15 s, then a fresh side connection is tried (D4a; live run).
  - An EB with no entries completes the block with `txCount = 0` (D3a; Fable).
  - A superseded start's startup failure is logged, not thrown (Compatibility; Opus).
  - The halt is published before `SYNC_HALTED` is reported (rule 2; Codex).
  - The `Session` change is stated precisely; `next`'s event-loop handshake is listed in Related findings.
