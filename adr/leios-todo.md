# Leios in Yaci: work tracker

This file tracks the Leios work in Yaci, step by step.
Each step is updated as it moves, so its status, branch and review notes stay current.

Status values: `todo`, `in progress`, `done (local)`, `waiting for approval`, `done`, `blocked`.
`done (local)` means the work is committed on a local branch only. Nothing is pushed or merged without explicit approval.

## Goal

Downstream apps such as Yaci Store keep working **without code changes** when Leios arrives.
Yaci resolves certified endorser blocks (EBs) itself and delivers complete blocks through the existing API.
The node-to-node (N2N) path must provide the full transaction data, because most downstream apps use N2N.

## Musashi baseline (what we build and test against)

Musashi is the public Leios test network. It is respun whenever wire formats change,
so everything here must be checked against the version that is **currently deployed**,
not the newest release tag.

**Deployed now: `prototype-2026w40a`** (checked 2026-10-19). This is the base we change against.

| Item | Value | How it was checked (2026-10-19) |
|---|---|---|
| Deployed node build | `ouroboros-leios` **`prototype-2026w40a`** (2026-10-06) | Operator page https://book.play.dev.cardano.org/adv-musashi.html: "The latest version available is ... prototype-2026w40a" |
| Node version string | `cardano-node 11.1.0.164` | KleioScan (2026-10-17). w41+ is built on node 11.2, so 11.1 confirms pre-w41. |
| Network magic | 164 | handshake |
| Public relay | `leios-node.play.dev.cardano.org:3001` | handshake accepts N2N v15 and v16 |
| Live wire check | old LeiosNotify numbering | Probe `[0]` (RequestNext) gets `[1, announcement]` back; the w42 form `[3]` gets the connection reset. Probe script: `adr/leios-evidence/leios_probe.py` |

Spec pins for the deployed w40a network (cardano-blueprint, `leios-prototype` history now merged into `main`):

| Spec file | Pin | Notes |
|---|---|---|
| `src/network/node-to-node/leios-notify/messages.cddl` | `c3a0e3c4` | last commit before MsgQuit/MsgCanceled and renumbering |
| `src/network/node-to-node/leios-fetch/messages.cddl` | `a0abf292` | last commit before renumbering and the w42 BlockTxs change |
| `src/ledger/eras/dijkstra.cddl` | `131cc95a` | 3-item block body, `block_transaction` with `is_valid`, header still has `protocol_version` |

What changed since PRs #167/#171 were written (w27, July 2026):
- **Transport (#167): codecs unchanged, one payload shape changed.** w40a uses the same message numbering and shapes as #167
  (notify RequestNext=0 … Done=5; fetch BlockRequest=0 … BlockTxs=3, Done=9). Verified against the Haskell codecs
  w40a actually builds (`LeiosDemoOnlyTestNotify.hs` / `LeiosDemoOnlyTestFetch.hs` at ouroboros-consensus `cbd10033`,
  pinned by the w40a `flake.lock`). The **vote** payload changed from `[slot, eb_hash, voter_id, sig]` (w27) to
  `[announcing_rb_hash, voter_id, sig]`; the core codec keeps votes opaque, so only tests/helpers that decode votes are affected.
- **Block format (#171): changed.** The body lost `invalid_transactions` and became
  `[transactions, leios_certificate/nil, peras_certificate/nil]`; each transaction is a
  `block_transaction = [body, witnesses, aux/nil, is_valid]`. Transaction body keys 24/27,
  pool `bls_key` and protocol-parameter keys up to 48 also arrived. This is why every live block fails today.

## Next respin: rc-2026w42 (prepared, not deployed)

`rc-2026w41` and `rc-2026w42` are release candidates. The w42 notes say "w41 was not yet used on a public testnet".
When Musashi is respun to w42, Yaci needs these extra changes (spec pin: cardano-blueprint `main` @ `0460c5a6`, 2026-10-16):

| Area | w40a (deployed) | w42 (next respin) |
|---|---|---|
| LeiosNotify tags | RequestNext=0, Announcement=1, Offer=2, TxsOffer=3, Votes=4, ClientDone=5 | Done=0, Quit=1, Canceled=2, RequestNext=3, Announcement=4, Offer=5, TxsOffer=6, Votes=7 |
| Notify shutdown | client sends ClientDone | client sends Quit, server answers Done; server may send Canceled |
| LeiosFetch tags | BlockRequest=0, Block=1, TxsRequest=2, BlockTxs=3, Done=9 | Done=0, BlockRequest=1, Block=2, TxsRequest=3, BlockTxs=4 |
| `MsgLeiosBlockTxs` | `[3, point, bitmaps, tx_list]` | `[4, [* #6.24(bytes .cbor block_transaction)]]` |
| Header index 9 | `protocol_version` | `header_version_info` (same `[uint, uint]` shape) |
| Ledger extras | | protocol-parameter keys 49–56, Plutus V4 scripts (witness key 8) |

The transport part is already written and unit-tested as a patch:
`adr/leios-patches/w42-transport-repin.patch` (applies to the rebased #167 branch; 1083 unit tests pass with it).
It is **not applied**, because the live network would reset the connection.

How to re-check the baseline:
1. Read the operator page above for the deployed build, and the latest tag on
   https://github.com/input-output-hk/ouroboros-leios/releases (`rc-` tags are candidates, not deployed).
2. Run `python3 adr/leios-evidence/leios_probe.py` to see which numbering the live relay answers.
3. Run the live tests (`LeiosMusashiSmokeTest`, `MusashiBlockSyncE2EIT`; see step 2b).
4. Update the tables above, including the date. The full pin history lives in
   `docs/leios/leios-spec-tracking.md` and the procedure in `docs/leios/leios-musashi-source-tracking-guide.md`.

## Steps

| # | Step | Status | Where | Review |
|---|---|---|---|---|
| 0 | Create this tracker and record the Musashi baseline | done | `adr/leios-todo.md` | n/a |
| 0b | Baseline: run the original #171 code against live Musashi (w40a) | done (fails as expected) | `adr/leios-evidence/baseline-w42-original-171.md` | n/a |
| 1 | Rebase PR #167 (LeiosNotify/LeiosFetch transport) onto `next-leios` | done (local) | branch `feat/leios_protocol_impl-rebase` @ `dce5a78`; clean rebase, 1079 tests pass | reviewed: OK (see step 1 review) |
| 1a | Transport for the deployed w40a network | done: no change needed, live smoke test passes | live relay | |
| 1b | Prepare the w42 transport re-pin for the next respin | done (patch, not applied) | `adr/leios-patches/w42-transport-repin.patch` | |
| 2 | Rebase PR #171 (Dijkstra serialization + listener) onto rebased #167 and resolve conflicts with `next` | done | branch `feat/leios_mushashi_serialization-rebase` | reviewed; fixes applied (see step 2 review) |
| 2a | Port `next`'s hardening to the Dijkstra block path (deep nesting, original bytes, full tx CBOR) | done | same branch | reviewed |
| 2c | Re-pin #171 block format to the deployed w40a (3-item body, `block_transaction`, tx body keys, pool BLS key) | done | same branch; 18 live blocks parse | reviewed |
| 2b | Check the rebased code against the live Musashi w40a network | done: PASS | `adr/leios-evidence/step2b-w40a-rebased-171.md` | |
| 3 | Write ADR 0013 (complete blocks) and the design doc with C4 block diagrams. **Owner review checkpoint.** | done: approved by owner 2026-10-19, with an added inline flag | `adr/0013-leios-complete-blocks.md`, `docs/leios/leios-complete-blocks-design.md` | review agent running |
| 4 | Build the complete-block assembler in the helper (announcement memory, EB resolver, reorder buffer, merger, ordered delivery) and `YaciConfig.inlineEndorserBlockTxs` (default true) | done | branch `feat/leios_complete_blocks` (PR stacked on #171) | reviewed; all findings addressed |
| 5 | N2N range sync: fetch historical EBs over LeiosFetch so N2N gives full tx data through the same interface | done with step 4: live range test matches db-sync | `MusashiCompleteBlocksIT` | |
| 6 | N2C: accept the node's inlined certifying blocks (secondary, most downstream uses N2N) | todo | | |
| 7 | Run an unmodified Yaci Store against Musashi and check tx counts | todo | | |
| 8 | Push / merge (each push or merge needs explicit approval) | push approved by owner 2026-10-19: rebased branches force-pushed to the PR #167 / #171 branches (old heads `1fa64f6` / `d7f538c` kept in local branches); **merge not done** | PRs #167, #171 | |

## Step notes

### 0b. Baseline result (2026-10-19, original #171 @ `d7f538c`, live Musashi w40a from tip block 162,938)
- Handshake OK (N2N v15). The default w27 intersect point (slot 540,887) no longer exists: the chain was respun.
- All 10 ranking blocks failed to parse: `Dijkstra block_body must have at least 4 items` (w40a has 3).
- 4 EBs fetched with matching hashes (17,087 tx refs). All marked incomplete; to be checked in 2b (the test caps tx fetch at 512 per EB).
- 120 votes decoded, 1 rollback, 4 disconnects.
- Re-run: `YACI_MUSASHI_E2E=true YACI_MUSASHI_E2E_INTERSECT_SLOT=<slot> YACI_MUSASHI_E2E_INTERSECT_HASH=<hash> YACI_MUSASHI_E2E_DURATION_SECONDS=240 ./gradlew :helper:integrationTest --tests '*MusashiBlockSyncE2EIT*'`

### 1. Rebase PR #167
- `next-leios` == `next` == `b8b7def` (0.5.0-pre14). Both PR branches start from `3787bc8` (2026-07-01), 40 commits behind.
- PR #167 changes only Leios packages plus `Constants.java`, so it should rebase cleanly.

### 1. Step 1 review (independent review agent, 2026-10-19)
Verdicts: rebase fidelity OK (PR diff byte-identical before/after), prod safety OK (only `Constants.java` gains a constant),
w40a wire codecs OK. Follow-ups:
1. (medium) Vote shape changed to 3 fields; `LeiosMusashiSmokeTest.decodeVote` still expects 4, and
   `docs/leios-spec-tracking.md` still pins `188183b`. Fix in step 2 (test + doc). **todo**
2. (medium) LeiosFetch `BlockTxs` has never been verified live (0b: `fetchedEndorserBlockTxs: 0`, 4 unexplained
   disconnects). Verify in step 2b and log disconnect causes. **todo**
3. (medium, w42 patch) A decode error while in `StQuit` resets to `StIdle` and sends a second `MsgQuit`.
   Treat any error in `StQuit` as terminal. **todo (patch)**
4. (low/medium) `LeiosRawCbor` holds re-encoded bytes, not wire bytes; slice raw bytes (as ADR 0009 does) before
   tx hashes are checked against the EB map (steps 4–5). **todo**
5. (low) Probe varies version and tag together; add `[3]` on v15 and `[0]` on v16; protocol 19 not probed. **todo**
6. (low) After a parse error in `StBusy` the notify agent re-requests; a Haskell peer treats it as fatal. Consider ending the protocol.
7. (low, w42 patch) Re-request right after `MsgCanceled` with no backoff; check the server's use of Canceled.
8. (low) Lenient validation: empty vote list, w40a BlockTxs echo vs request, tx count vs bitmap popcount.

### 2. Rebase PR #171
- One textual conflict: `core/.../serializers/BlockSerializer.java` (both sides rewrote datum/redeemer correction).
- Resolution rule: take `next`'s file as the base, then re-add only the Leios hooks
  (Dijkstra dispatch, `populateLegacyDijkstraCertificates`, `isDijkstraRestructuredBlock`).
  Reuse `next`'s `handleWitnessDatumRedeemer(...)` from `DijkstraBlockBodySerializer`; drop the Leios copy `fixWitnessDatumRedeemer`.
- Auto-merged but must be checked by hand: `EraUtil.java`, `TransactionBodySerializer.java`.

### 2. What was done (2026-10-19)
- Rebase of the 7 #171 commits onto `feat/leios_protocol_impl-rebase`. One conflict, in `BlockSerializer`, resolved by taking
  `next`'s file and adding only the Dijkstra hook. The pre-w27 "legacy segmented" Dijkstra shape
  (`populateLegacyDijkstraCertificates`) was dropped: no Musashi chain has used it since the w27 respin.
- **API compatibility check** (`adr/leios-evidence/apicompare.py`, `javap` over every public type, `next` jars vs rebased jars):
  found two breaks introduced by #171, both fixed by restoring the old constructors:
  `Block(era, header, bodies, witnesses, aux, invalid, cbor)` and the 13-argument `HeaderBody(...)`.
  Result now: core 793 types, helper 80 types, **0 incompatibilities**.
- Uncommitted working-tree changes on top of the rebase (nothing is committed without approval):
  - `BlockSerializer`: +4 lines at the entry point (Dijkstra blocks go to `DijkstraBlockBodySerializer`, decided by
    reading only the era tag) and 3 helpers made package-visible. No other change to the Shelley–Conway path.
  - `DijkstraTransactionExtractor`: rewritten on `next`'s `CborSlice` (no recursion), w40a layout.
  - `DijkstraBlockBodySerializer`: per-transaction reuse of `next`'s fixes: normal parse, then `DataItemIsolation`
    on a stack overflow or data error; raw witness/aux CBOR; aux hash check; datum/redeemer byte fix-up.
  - `PoolRegistrationSerializer`: accepts the Dijkstra 11-item form (optional `bls_key`); Conway path unchanged.
    The BLS key itself is not modelled yet (adding a field would change `PoolParams`' public constructor).
  - `EndorserBlockTxListSerializer`: w40a tx list `[* bytes]`, EB tx reference hash = blake2b-256 of the inner bytes.
  - Tests: w40a body/tx tests, deep-nesting on a 512 KB stack, raw witness/aux bytes, 5 live w40a blocks
    (`core/src/test/resources/leios/w40a/`, with a README), Dijkstra pool registration, w40a votes in the smoke test.
  - Capture tool now also saves blocks it cannot parse (`-PfixtureDir`, `-PfixtureSeconds`).
- Build: `./gradlew build` passes, 1119 unit tests, 0 failures.

### 2b. Live result (2026-10-19, rebased #171 + working tree, from tip block 163,018, 240 s)
- PASS. 9 ranking blocks, **0 parse errors** (baseline: 10 of 10 failed). 1,770 txs, 7 announcements, 3 certifying blocks;
  2 certifications matched the parent's announcement (the third parent was before the window).
- 6 EBs fetched, all hashes match. 82 votes decoded (3-field w40a shape).
- EB txs were not fetched: the coordinator waits for a `BlockTxsOffer`, and the relay never sends one to us.
  Each EB timed out after 2 s as "refs only". Step 4 must request EB txs without waiting for an offer.
- 4 "disconnects" counted but only one connection was opened in the log; cause still to be explained (review follow-up 2).

### 2. Step 2 review (independent review agent, 2026-10-19)
Verdicts: conflict resolution OK (prod path textually identical to `next` except the 4-line entry check and 3
visibility changes); other prod paths OK; Dijkstra hardening matches `next` (deep native script and deep
metadata isolation also confirmed by the reviewer's probes); w40a block shape OK.
Findings and what was done:
1. (medium, prod-visible) JSON of every Conway `Block`/`HeaderBody` gained `null` Leios/Peras keys.
   **Fixed**: `@JsonInclude(NON_NULL)` on the 4 fields, as `next` does; test `conwayBlockJsonHasNoLeiosOrPerasKeys`.
2. (medium) Full tx CBOR for Dijkstra used the Alonzo order. **Fixed**: `TxCborUtil` Dijkstra branch builds
   `[body, wits, aux/nil, is_valid]`; the live-fixture test checks it is byte-identical for every transaction.
3. (medium) One credential guard (key 14) failed the whole block. **Fixed**: key-hash credentials are kept as
   required signers, script guards are skipped with a debug log; test added.
4. (low-medium) Dijkstra additions not modelled: nested `script_require_guard` in native-script JSON, redeemer
   tag 6 (guarding), sub-transactions (key 23). **Open** (known gaps, step 4+).
5. (low) `EndorserBlockTx.txHash` is the EB reference hash (of the full tx bytes), not the tx id. **Open**:
   document or rename in step 4, derive the tx id from the body.
6. (low) Missing tests: header-hash chain across live fixtures, aux-hash mismatch. **Open**. Unused
   `core/src/test/resources/leios/w27` fixtures kept as the record of the old pin.
7. (low) Stale docs. **Fixed** for `LeiosEndorserBlockFetcher` and the usage-guide banner; full usage-guide
   rewrite in step 4. Release note needed: `Era.Dijkstra` can break downstream `switch` statements without default.

### 3. ADR review (independent review agent, 2026-10-19)
High: announcements after restart/rollback, back-pressure deadlock, range sync not attaching Leios. Medium:
failure signal, callbacks bypassing the queue, prefetch rule, evidence wording, N2C. All addressed in the
revised ADR and design doc: announcements keyed by RB hash and seeded from the start point; BlockFetch requested
in parts instead of pausing the socket; `AUTO` attaches LeiosFetch for range sync on Musashi with a fail-fast
check; strict mode waits and retries with a visible callback; one ordered delivery queue for all callbacks;
early fetch only for EBs this peer offered; evidence stated as "first 8 transactions"; N2C certifying block
without EB transactions counts as unresolved; one setting per concern. Added prerequisites before the default
is switched on: full-EB live fetch, explanation of the 4 disconnects, longer offer observation.

### 2a. Dijkstra hardening (gaps that do not show up as conflicts)
- Deeply nested datum or native script: `next`'s fallback `deserializeWithIsolatedData` assumes the Shelley–Conway layout and fails on Dijkstra blocks. Needs a per-transaction isolation path.
- Witness and auxiliary-data CBOR must come from the original bytes; aux-data hash must be validated.
- Helper `BlockFetchAgentListenerAdapter` rebuilds full tx CBOR with `TxCborUtil`; Dijkstra and EB transactions need the same.
- `next`'s regression tests must pass unchanged. Add Dijkstra versions of the deep-nesting and raw-bytes tests.
- Fix the failed SonarCloud quality gate on #171 (reliability rating C).

### 1b / 2c. Re-pin checklist (from the CDDL diffs: w27 `cb1de23` → w40a `131cc95a` → w42 `0460c5a6`)

Transport (#167 branch), **w42 only** (prepared in the patch, not needed for w40a):
- LeiosNotify: renumber to `Done=0, Quit=1, Canceled=2, RequestNext=3, Announcement=4, BlockOffer=5, BlockTxsOffer=6, Votes=7`; add `MsgQuit` and `MsgCanceled`.
- LeiosFetch: `Done=0, BlockRequest=1, Block=2, BlockTxsRequest=3, BlockTxs=4`.
- `MsgLeiosBlockTxs` = `[4, [* #6.24(bytes .cbor block_transaction)]]`; no echoed point or bitmaps any more.
- `MsgLeiosVotes` = `[7, [1* [announcing_rb_hash, voter_id(word16), signature(48 bytes)]]]`.
- EB (`MsgLeiosBlock`) = `{ + tx_hash => word32 }`, non-empty. Tx hash and size are of the bytes inside the `#6.24` wrapper.

Block format (#171 branch), **needed now for w40a** (also valid for w42 except where marked):
- Header body: index 9 is `protocol_version` in w40a and `header_version_info` in w42 (same `[uint, uint]` shape). Index 10 is the certified flag and index 11 the EB announcement in both, same positions as w27.
- Block body: `[transactions, leios_certificate / nil, peras_certificate / nil]`. `invalid_transactions` is removed.
- Each body transaction is a `block_transaction = [body, witness_set, aux / nil, is_valid]`. Invalid tx indexes now come from `is_valid == false`.
- One transaction-slice reader should serve both the RB body and the EB tx list, since both use `block_transaction`.
- Transaction body: new keys `24` (required top-level guards) and `27` (starting account balance intervals).
- Pool registration: optional `bls_key = [bls_pubkey(96 bytes), proof(48 bytes)]` after `vrf_keyhash`.
  `PoolRegistrationSerializer` reads fields by position (index 3 = pledge), so a Dijkstra registration with a
  BLS key would fail. Detect the extra field by type and length (Conway always has an integer at index 3),
  so the Conway path stays byte-for-byte unchanged.
- Protocol parameter update: new keys 38–48 in w40a (Leios periods, committee, EB limits); 49–56 in w42 (Peras, ref-input fees).
- w42 only: Plutus V4 scripts in the witness set (key 8).

### 4. Complete blocks (step 4, 2026-10-19)
What was built (branch `feat/leios_complete_blocks`):
- `CompleteBlockAssembler` wraps the app listener (only when `LeiosConfig.shouldInlineEndorserBlockTxs(magic)`):
  announcement memory by RB hash, ordered bounded queue (256) delivered on one thread, waits for EB transactions.
- `EndorserBlockResolver`: separate Leios connection (`LeiosNetworkClient`), verifies EB hash and every tx.
- `ParentBlockLookup`: parent never seen → ChainSync `FindIntersect` over 1,000 candidate slots + BlockFetch.
- Core: `DijkstraBlockBodySerializer.mergeEndorserBlockTransactions`, EB tx reader for all 3 encodings,
  `LeiosEndorserBlockRef`, `Block.leiosEndorserBlock`, `YaciConfig.inlineEndorserBlockTxs` (default true),
  `LeiosConfig.skipUnavailableEndorserBlocks` (default false), `onEndorserBlockUnavailable` callback.
- `TransactionListBuilder`: the adapter's transaction-list code moved unchanged (diff-checked) for reuse.
- **Bug found and fixed (from #167):** `LeiosFetchAgent` cleared the outstanding request *after* calling listeners,
  so a request sent from inside `onBlock` (EB → its transactions) was dropped and its answer never matched;
  the error had no point and the request hung. Fixed by clearing before the callbacks; regression test fails on
  the old code and passes on the new.
Evidence:
- Unit: 1,135 tests, 0 failures; API check 0 incompatibilities (core, helper).
- Live `MusashiCompleteBlocksIT` (unmodified listener), all = db-sync (KleioScan):
  range #156612..#156615 → 354, **345**, **5,255** (last tx `c82dae27…2327`), 356, ~6 s;
  range starting at a certifying block → parent found, same counts;
  tip sync 4 min → 12 blocks, 5 certifying, all complete (5,555 / 5,555 / 559 / 4,085).
- EB fetch through Yaci: 345 txs in 1.1 s, 5,255 txs (1.3 MB) in 1.05 s.
Step 4 review (independent review agent) and what was done:
1. (high) **Deadlock** when stopping while the queue is full: `stop()`/`restart()`/`startSync()` shut the connection
   down (needs the network thread) before closing the assembler (frees that thread). Reachable from Yaci Store's
   `stopSyncOnError`. **Fixed**: assembler closed first; regression test `stopWhileQueueIsFullDoesNotDeadlock`.
2. (medium) Retry after timeout reused the stuck request. **Fixed**: pending request cancelled on timeout; a peer
   without Leios fails new requests at once with that reason. Test `timedOutEndorserBlockIsRequestedAgain`.
3. (medium) A failed LeiosFetch agent was never reset. **Fixed**: next request starts a new Leios connection
   (from the caller's thread, never the connection's own thread).
4. (medium) `close()` interrupted application code. **Fixed**: no interrupt; the waiting block is woken instead.
   Test `closeNeverInterruptsApplicationCode`.
5. (low-medium) Rollback race between `take()` and `current`. **Fixed**: rollbacks recorded with a sequence
   number; a certifying block checks rollbacks since its arrival before every wait.
6. (low-medium) Leaked selectors when an assembler never resolved an EB. **Fixed**: Leios client created lazily.
7. (low) `onBlock` exceptions used to reach `onParsingError`. **Fixed** to keep that behaviour (test added);
   `onEndorserBlockUnavailable` exceptions guarded; tip sync uses the intersection point as the known parent;
   1,000-slot search limit and duplicate notify traffic documented.
8. (low) Doc mismatches. **Fixed** in ADR 0013, design doc and usage guide.
After fixes: 1,139 unit tests pass; API check 0 incompatibilities; live `MusashiCompleteBlocksIT` 3/3.

ADR 0013 prerequisites: all done.
- 2: the "4 disconnects" were 2 events reported twice (both adapters forward `onDisconnect`; existing behaviour).
- 3: 10-minute run: 16 EB offers, 5 transactions offers (`adr/leios-evidence/observation-10min-2026-10-19.txt`).

### 5. N2N must provide full tx data
- Requirement (owner, 2026-10-19): N2N must deliver the full transaction data through the same interface.
  N2C is secondary.
- Open question: do Musashi peers serve **old** EBs over LeiosFetch? Range sync depends on it.
- **Answer (live probe, 2026-10-19, w40a): yes for certified EBs.** `adr/leios-evidence/leios_fetch_probe.py` sends
  `MsgLeiosBlockRequest` then `MsgLeiosBlockTxsRequest` (first 8 txs) with **no prior offer**:

  | EB | Age | EB served | Txs served | tx hashes = EB refs |
  |---|---|---|---|---|
  | `c6186243…` (slot 3,640,406) | ~15 min | yes, 19,301 B | yes | 8/8 |
  | `3bca1a2b…775a` (slot 3,495,634, certified by #156613) | 2 days | yes, 12,425 B (= announced 12,423 + framing) | yes | 8/8 |
  | `0eaab108…2bb7` (slot 1,334,920, block #59,996) | 4 weeks | yes, 84,245 B | yes | 8/8 |
  | `ebfe2e7c…6b94` (slot 3,495,671, never certified) | 2 days | empty map `{}` | empty list | n/a |

  So certified EBs are kept and served on request (at least 4 weeks back, i.e. since early in the current chain);
  uncertified EBs are discarded. N2N can therefore provide the full transaction data for range sync.
  Note for w41+: the server "disconnects a request it cannot answer", so Yaci must only ask for certified EBs.
- **ADR 0013 prerequisite 1 met (2026-10-19):** a full certified EB fetched in **one** `MsgLeiosBlockTxsRequest`:
  EB `77714b7b…08be` (slot 3,495,655, certified by #156614, 2 days old): 5,255 txs, 1,309,375 bytes in 0.7–1.0 s,
  **5,255/5,255** tx hashes match the EB references
  (`python3 adr/leios-evidence/leios_fetch_probe.py 3495655 77714b7b…08be "" all`).
  w40a EB transactions are the 3-element mempool form `[body, witnesses, aux/nil]` (16 of 16 sampled).
