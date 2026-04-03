# Ledger-State Module Design

The `ledger-state` module is the RocksDB-backed persistence layer for Cardano ledger state in Yaci. It tracks stake accounts, delegations, pool registrations, DRep governance state, and epoch boundary transitions including reward calculation.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [How It Fits in the Sync Pipeline](#how-it-fits-in-the-sync-pipeline)
- [RocksDB Column Families](#rocksdb-column-families)
- [Key/Value Format Reference](#keyvalue-format-reference)
- [Block Processing Flow](#block-processing-flow)
- [Epoch Boundary Flow](#epoch-boundary-flow)
- [Delegation Snapshot](#delegation-snapshot)
- [Conway Governance](#conway-governance)
- [Two-Phase Governance Commit](#two-phase-governance-commit)
- [Delta Journal & Rollback](#delta-journal--rollback)
- [Epoch Snapshot Export](#epoch-snapshot-export)
- [Module Dependencies](#module-dependencies)

---

## Architecture Overview

```
                          BodyFetchManager
                                |
                       BlockAppliedEvent
                                |
                    AccountStateEventHandler
                          /         \
              applyBlock()    epoch transition events
                  |               |
    DefaultAccountStateStore   EpochBoundaryProcessor
         |          |              |          |
    certificates  governance   rewards   governance
    withdrawals   block proc   snapshot  epoch proc
         |          |              |          |
         +-------- RocksDB --------+----------+
              (4 column families)
```

**Key classes:**
- `DefaultAccountStateStore` — block-level state mutations (certs, withdrawals, deposits)
- `EpochBoundaryProcessor` — orchestrates reward calculation, snapshots, governance
- `GovernanceBlockProcessor` — processes proposals, votes, DRep/committee certs per block
- `GovernanceEpochProcessor` — ratification, enactment, DRep distribution at epoch boundaries
- `AccountStateEventHandler` — routes EventBus events to store methods

---

## How It Fits in the Sync Pipeline

```
Cardano Node (n2n)
    |
    v
BodyFetchManager (node-runtime)
    |--- Block received, stored in ChainState
    |--- publishEpochTransitionEventsIfNeeded() [at epoch boundary]
    |
    v
EventBus publishes:
    1. PreEpochTransitionEvent    --> rewards + snapshot + governance
    2. EpochTransitionEvent       --> prune old data + credit reward_rest
    3. PostEpochTransitionEvent   --> (currently no-op)
    4. BlockAppliedEvent          --> process block certificates + governance
```

All processing is **synchronous on the Netty event loop** (single-threaded per connection). Epoch boundary events fire BEFORE the first block of the new epoch is applied.

---

## RocksDB Column Families

| Column Family | Constant | Purpose |
|--------------|----------|---------|
| `acct_state` | `ACCT_STATE` | Primary store: accounts, delegations, pools, governance state |
| `acct_delta` | `ACCT_DELTA` | Delta journal: rollback operations keyed by block number |
| `epoch_deleg_snapshot` | `EPOCH_DELEG_SNAPSHOT` | Epoch-scoped delegation snapshots (retained 50 epochs) |
| `epoch_params` | `EPOCH_PARAMS` | Protocol parameter history per epoch |

All governance state (prefixes `0x60`-`0x6D`) lives in `acct_state` alongside account data.

---

## Key/Value Format Reference

All values use **CBOR encoding** with integer-keyed maps: `{0: field0, 1: field1, ...}`.

### Account & Delegation Prefixes (in `acct_state`)

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_ACCT` | `0x01` | `credType(1) + credHash(28)` | `{0: reward, 1: deposit}` | Stake account. Deleted on deregistration. |
| `PREFIX_POOL_DELEG` | `0x02` | `credType(1) + credHash(28)` | `{0: poolHash(bstr28), 1: slot, 2: txIdx, 3: certIdx}` | Active pool delegation |
| `PREFIX_DREP_DELEG` | `0x03` | `credType(1) + credHash(28)` | `{0: drepType, 1: drepHash(bstr), 2: slot, 3: txIdx, 4: certIdx}` | DRep delegation (governance vote power) |

**Credential types:** `0` = ADDR_KEYHASH, `1` = SCRIPT_HASH

**DRep types:** `0` = key hash, `1` = script hash, `2` = ABSTAIN, `3` = NO_CONFIDENCE

### Pool Prefixes

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_POOL_DEPOSIT` | `0x10` | `poolHash(28)` | `{0: deposit, 1: marginNum, 2: marginDen, 3: cost, 4: pledge, 5: rewardAccount(bstr), 6: owners(array)}` | Pool registration params |
| `PREFIX_POOL_RETIRE` | `0x11` | `poolHash(28)` | `{0: retireEpoch}` | Planned retirement |
| `PREFIX_POOL_PARAMS_HIST` | `0x12` | `poolHash(28) + activeEpoch(4 BE)` | Same as pool deposit | Historical params by active epoch |
| `PREFIX_POOL_REG_SLOT` | `0x13` | `poolHash(28)` | `slot(8 BE)` | Latest registration slot (stale deleg detection) |

### Stake Lifecycle Prefixes

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_ACCT_REG_SLOT` | `0x14` | `credType(1) + credHash(28)` | `slot(8 BE)` | Latest registration slot |
| `PREFIX_DREP_REG` | `0x20` | `credType(1) + credHash(28)` | `{0: deposit}` | DRep registration deposit |
| `PREFIX_STAKE_EVENT` | `0x55` | `slot(8 BE) + txIdx(2 BE) + certIdx(2 BE) + credType(1) + credHash(28)` | `{0: eventType}` | Registration (0) / deregistration (1) events |

### Epoch-Scoped Prefixes (Reward Calculation)

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_POOL_BLOCK_COUNT` | `0x50` | `epoch(4 BE) + poolHash(28)` | `{0: blockCount}` | Blocks produced per pool per epoch |
| `PREFIX_EPOCH_FEES` | `0x51` | `epoch(4 BE)` | `{0: totalFees}` | Total tx fees collected in epoch |
| `PREFIX_ADAPOT` | `0x52` | `epoch(4 BE)` | `{0: treasury, 1: reserves, 2: deposits, 3: fees, 4: distributed, 5: undistributed, 6: rewardsPot, 7: poolRewardsPot}` | Ada pot snapshot |
| `PREFIX_REWARD_REST` | `0x56` | `spendableEpoch(4 BE) + type(1) + credType(1) + credHash(28)` | `{0: amount, 1: earnedEpoch, 2: slot}` | Deferred rewards (proposal refunds, treasury withdrawals) |

**Reward rest types:** `0` = proposal refund, `1` = treasury withdrawal

### Governance Prefixes (`0x60`-`0x6D`, in `acct_state`)

| Prefix | Byte | Key | Value | Description |
|--------|------|-----|-------|-------------|
| `PREFIX_GOV_ACTION` | `0x60` | `txHash(32) + govIdx(2 BE)` | `{0: deposit, 1: returnAddr, 2: proposedEpoch, 3: expiresAfter, 4: actionType, 5: prevTxHash, 6: prevIdx, 7: govActionCbor, 8: slot}` | Governance proposals |
| `PREFIX_VOTE` | `0x61` | `txHash(32) + govIdx(2 BE) + voterType(1) + voterHash(28)` | Single vote byte | Votes: NO=0, YES=1, ABSTAIN=2 |
| `PREFIX_DREP_STATE` | `0x62` | `credType(1) + credHash(28)` | `{0: deposit, 1: anchorUrl, 2: anchorHash, 3: regEpoch, 4: lastInteractionEpoch, 5: expiryEpoch, 6: active, 7: regSlot, 8: protocolVersion, 9: prevDeregSlot}` | DRep state |
| `PREFIX_COMMITTEE_MEMBER` | `0x63` | `credType(1) + coldHash(28)` | `{0: hotCredType, 1: hotHash, 2: expiryEpoch, 3: resigned}` | Committee members |
| `PREFIX_CONSTITUTION` | `0x64` | (singleton) | `{0: anchorUrl, 1: anchorHash, 2: scriptHash}` | Current constitution |
| `PREFIX_DORMANT_EPOCHS` | `0x65` | (singleton) | Array of epoch integers | Dormant epochs (no active proposals) |
| `PREFIX_DREP_DIST` | `0x66` | `epoch(4 BE) + credType(1) + drepHash(28)` | `{0: stake}` | DRep distribution per epoch |
| `PREFIX_EPOCH_PROPOSALS_FLAG` | `0x67` | `epoch(4 BE)` | Marker | Epoch had active proposals |
| `PREFIX_EPOCH_DONATIONS` | `0x68` | `epoch(4 BE)` | `{0: amount}` | Treasury donations per epoch |
| `PREFIX_LAST_ENACTED` | `0x69` | `actionType(1)` | `{0: txHash(bstr32), 1: govIdx}` | Last enacted action per type |
| `PREFIX_RATIFIED_IN_EPOCH` | `0x6A` | Sequential key | Pending enactment entries | Ratified proposals awaiting enactment |
| `PREFIX_COMMITTEE_THRESHOLD` | `0x6B` | (singleton) | `{0: numerator, 1: denominator}` | Committee quorum threshold |
| `PREFIX_EXPIRED_IN_EPOCH` | `0x6C` | Sequential key | Pending drop entries | Expired proposals awaiting deposit refund |
| `PREFIX_PROPOSAL_SUBMISSION` | `0x6D` | `slot(8 BE)` | `{0: epoch, 1: govActionLifetime}` | Permanent proposal metadata (v9 DRep bonus) |

### Metadata Keys (string keys in `acct_state`)

| Key | Value | Description |
|-----|-------|-------------|
| `"total_dep"` | BigInteger bytes | Total deposited lovelace |
| `"meta.last_block"` | Block number bytes | Last applied block |
| `"meta.last_snapshot_epoch"` | Epoch (4 BE) | Most recent snapshot epoch |
| `"mir.to_reserves"` | BigInteger bytes | MIR transfers to reserves |
| `"mir.to_treasury"` | BigInteger bytes | MIR transfers to treasury |

### Epoch Delegation Snapshot (in `epoch_deleg_snapshot`)

| Key | Value | Description |
|-----|-------|-------------|
| `epoch(4 BE) + credType(1) + credHash(28)` | `{0: poolHash(bstr28), 1: amount}` | Delegation with stake amount |

Retained for last 50 epochs. Used by reward calculation (snapshot at N-4) and DRep distribution.

---

## Block Processing Flow

`DefaultAccountStateStore.applyBlock()` is called for every block:

```
For each VALID transaction (skip invalidTransactions):

1. Process Certificates
   |- StakeRegistration/RegCert      --> create PREFIX_ACCT, write PREFIX_STAKE_EVENT, PREFIX_ACCT_REG_SLOT
   |- StakeDeregistration/UnregCert  --> delete PREFIX_ACCT, write PREFIX_STAKE_EVENT, return deposit
   |- StakeDelegation               --> write PREFIX_POOL_DELEG
   |- VoteDelegCert/StakeVoteDelegCert --> write PREFIX_DREP_DELEG (+ PREFIX_POOL_DELEG if combo)
   |- PoolRegistration              --> write PREFIX_POOL_DEPOSIT, PREFIX_POOL_PARAMS_HIST
   |- PoolRetirement                --> write PREFIX_POOL_RETIRE
   |- RegDrepCert                   --> write PREFIX_DREP_REG + GovernanceBlockProcessor.processDRepRegistration()
   |- UnregDrepCert                 --> delete PREFIX_DREP_REG + GovernanceBlockProcessor.processDRepDeregistration()
   |- UpdateDrepCert                --> GovernanceBlockProcessor.processDRepUpdate()
   |- AuthCommitteeHotCert          --> GovernanceBlockProcessor.processCommitteeHotKeyAuth()
   |- ResignCommitteeColdCert       --> GovernanceBlockProcessor.processCommitteeResignation()

2. Process Withdrawals
   |- For each reward withdrawal: debit PREFIX_ACCT.reward

3. Governance Block Processing (GovernanceBlockProcessor.processBlock)
   |- Process proposals   --> write PREFIX_GOV_ACTION, PREFIX_PROPOSAL_SUBMISSION
   |- Process votes       --> write PREFIX_VOTE, update PREFIX_DREP_STATE.lastInteractionEpoch
   |- Accumulate donations --> aggregate per block, write PREFIX_EPOCH_DONATIONS once

4. Track Block Count & Fees
   |- PREFIX_POOL_BLOCK_COUNT[epoch][poolHash] += 1
   |- PREFIX_EPOCH_FEES[epoch] += block fees

5. Commit
   |- Collect DeltaOps throughout processing
   |- Write delta journal to cfDelta[blockNo]
   |- Update META_LAST_APPLIED_BLOCK
   |- db.write(WriteBatch) -- atomic commit
```

---

## Epoch Boundary Flow

Three-phase transition matching the Cardano ledger EPOCH rule:

### Phase 1: PreEpochTransitionEvent

`EpochBoundaryProcessor.processEpochBoundary(previousEpoch, newEpoch)`

```
Step 1: Finalize protocol parameters
        paramTracker.finalizeEpoch(newEpoch)

Step 2: Bootstrap AdaPot (once at Shelley start)

Step 3: Calculate & distribute rewards (epoch >= 2)
        - Stake snapshot from epoch N-4
        - Pool block counts from epoch N-2
        - Fees from epoch N-1
        - Uses cf-rewards library (EpochCalculation)
        - Distributes to PREFIX_ACCT.reward per credential
        - Updates AdaPot (treasury, reserves, distributed)

Step 4: Create delegation snapshot (SNAP)
        createAndCommitDelegationSnapshot(previousEpoch)
        - Iterates PREFIX_POOL_DELEG
        - Validates: registered, not retired pool, not stale, not deregistered
        - Computes: UTXO balance + rewards + reward_rest
        - Writes to cfEpochSnapshot
        - Returns utxoBalances for governance DRep distribution

Step 4b: Pool deposit refunds (POOLREAP)
         - After snapshot, before governance
         - Credits to PREFIX_REWARD_REST

Step 5: Conway governance epoch processing
        GovernanceEpochProcessor.processEpochBoundaryAndCommit()
        - Two-phase commit (see below)
        - Returns treasuryDelta + donations

Step 6: Apply governance treasury adjustment to AdaPot

Step 7: Verify AdaPot against expected values (JSON on classpath)
        - System.exit(1) on mismatch to prevent compounding errors
```

### Phase 2: EpochTransitionEvent

`DefaultAccountStateStore.handleEpochTransitionSnapshot()`

```
- Prune old snapshots (> 50 epochs)
- Prune old stake events
- Credit spendable reward_rest to PREFIX_ACCT.reward
  (reward_rest with spendableEpoch <= previousEpoch becomes withdrawable)
```

### Phase 3: PostEpochTransitionEvent

Currently no-op (POOLREAP moved to Phase 1 Step 4b).

---

## Delegation Snapshot

Created at each epoch boundary in `createAndCommitDelegationSnapshot()`.

**What gets included:**
- Every `PREFIX_POOL_DELEG` entry where the credential is registered (`PREFIX_ACCT` exists)
- Excluding: retired pools, stale delegations (pool re-registered after delegation), deregistered credentials
- Stake amount = UTXO balance + withdrawable rewards + spendable reward_rest

**Three layers of stale delegation detection:**
1. **Pool retirement:** `poolHash` in retired pool set (retireEpoch <= epoch)
2. **Pool re-registration:** delegation slot < `PREFIX_POOL_REG_SLOT` value
3. **Credential deregistration:** delegation slot < latest deregistration from `PREFIX_STAKE_EVENT` or `PREFIX_ACCT_REG_SLOT`

**Storage:** `cfEpochSnapshot` column family, key = `epoch(4 BE) + credType(1) + credHash(28)`.

**Retention:** 50 epochs. Pruned at each `EpochTransitionEvent`.

**Used by:**
- Reward calculation (snapshot from epoch N-4)
- DRep distribution calculation (current epoch snapshot)

---

## Conway Governance

### Block-Level Processing (`GovernanceBlockProcessor`)

For each block:
- **Proposals:** Parse `ProposalProcedure` from transactions, store as `PREFIX_GOV_ACTION`
- **Votes:** Parse `VotingProcedures`, store as `PREFIX_VOTE`, update DRep `lastInteractionEpoch`
- **DRep certs:** Registration creates `PREFIX_DREP_STATE`, deregistration marks inactive, update changes anchor
- **Committee certs:** Hot key authorization stores/updates `PREFIX_COMMITTEE_MEMBER`, resignation marks resigned
- **Donations:** Accumulated per block (not per tx) to avoid WriteBatch visibility issues, stored as `PREFIX_EPOCH_DONATIONS`

### Epoch-Level Processing (`GovernanceEpochProcessor`)

At each epoch boundary (Conway era, protocol >= 9):

1. **DRep Distribution** — For each DRep delegation: sum UTXO + rewards + reward_rest + proposal deposits
2. **Ratification** — Evaluate all active proposals against vote thresholds (committee, DRep, SPO)
3. **Enactment** — Apply previously ratified actions (UpdateCommittee, ParameterChange, HardFork, etc.)
4. **Deposit Refunds** — Return deposits for enacted/expired proposals via `PREFIX_REWARD_REST`
5. **DRep Expiry** — Recalculate expiry based on dormant epochs, activity, and v9/v10 formulas
6. **Dormant Tracking** — Track epochs with no active proposals (affects DRep expiry)
7. **Donations** — Add to treasury

---

## Two-Phase Governance Commit

The governance epoch processor uses two separate `WriteBatch` commits to ensure enactment changes are visible to ratification:

```
Phase 1: ENACT (processEnactmentPhase)
    - Bootstrap Conway genesis (first Conway epoch only)
    - Apply pending ratified proposals:
      - UpdateCommittee: add/remove members, preserve pre-authorized hot keys
      - ParameterChange: update protocol params
      - HardForkInitiation: update protocol version
      - NoConfidence: clear all committee members
      - NewConstitution: update constitution
      - TreasuryWithdrawals: compute treasury delta
    - Update PREFIX_LAST_ENACTED per action type
    --> db.write(batch1)  [COMMITTED - changes now visible]

Phase 2: RATIFY + REST (processRatificationPhase)
    - Calculate DRep distribution
    - Build active DRep key set (non-expired DReps in distribution)
    - Read committee state [sees Phase 1 changes!]
    - Read lastEnactedActions [sees Phase 1 changes!]
    - Evaluate all active proposals against thresholds
    - Store newly ratified as pending enactment (PREFIX_RATIFIED_IN_EPOCH)
    - Store newly expired as pending drop (PREFIX_EXPIRED_IN_EPOCH)
    - Process deposit refunds for previously pending enactments/drops
    - Update dormant epochs, DRep expiry, donations
    --> db.write(batch2)
```

**Why two phases?** Without Phase 1 commit, an `UpdateCommittee` enactment writes new committee members to the WriteBatch, but `getAllCommitteeMembers()` reads from committed RocksDB state and doesn't see them. A subsequent `ParameterChange` proposal that requires committee approval would fail because the new committee is invisible.

---

## Delta Journal & Rollback

Every `applyBlock()` records a delta journal for rollback support:

```
DeltaOp = (opType, key, previousValue)
    opType: OP_PUT (0x01) or OP_DELETE (0x02)
    key: full byte key including prefix
    previousValue: value before this block's mutation (null if new)

Storage: cfDelta[blockNumber] = CBOR-encoded list of DeltaOps
```

On `rollbackTo(blockNumber)`:
1. Read all `cfDelta` entries with blockNumber >= target
2. Apply in reverse order (newest first)
3. For `OP_PUT`: restore `previousValue` (or delete if was new)
4. For `OP_DELETE`: restore `previousValue`
5. Update `META_LAST_APPLIED_BLOCK`

---

## Epoch Snapshot Export

Optional Parquet export for debugging and DBSync cross-verification.

**Architecture:** SPI interface (`EpochSnapshotExporter`) in `ledger-state`, implementation (`ParquetEpochSnapshotExporter`) in `epoch-export` module via ServiceLoader.

**Output:** Hive-partitioned Parquet files in `data/epoch=N/`:
- `epoch_stake.parquet` — stake delegations with bech32 `stake_address` and `pool_id`
- `drep_dist.parquet` — DRep distribution with bech32 `drep_id`
- `adapot.parquet` — treasury, reserves, deposits, fees
- `proposal_status.parquet` — ratification results with `gov_action_id`

**Querying:**
```sql
-- DuckDB reads Hive partitions automatically
SELECT * FROM 'data/epoch=*/drep_dist.parquet' WHERE epoch = 280;
SELECT epoch, count(*) FROM 'data/epoch=*/adapot.parquet' GROUP BY epoch;
```

**Cross-verification:** `verify.sh` compares exports against DBSync and Yaci-Store.

Enabled via `yaci.node.snapshot-export.enabled=true`. Zero overhead when disabled (NOOP pattern).

---

## Module Dependencies

```
ledger-state
    |- node-api       (AccountStateStore interface, events, EpochParamProvider)
    |- core           (Block, Transaction, Certificate, Governance models)
    |- rocksdb        (persistence)
    |- cbor           (CBOR serialization)
    |- cardano-client-core  (Credential types)
    |- cf-rewards-calculation (epoch reward calculation engine)
    |- events-processor (annotation processing for @DomainEventListener)

Dependents:
    |- node-runtime   (creates and wires DefaultAccountStateStore, YaciNode)
    |- node-app       (REST endpoints, configuration)
    |- epoch-export   (Parquet snapshot exporter)
```
