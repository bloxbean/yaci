# ADR-020: Conway-Era Governance State Tracking

**Status:** Proposed
**Date:** 2026-03-29
**Authors:** Satya
**Depends on:** ADR-018 (Epoch Snapshots and Reward Calculation)

## Context

Yaci's `ledger-state` module computes correct AdaPot/rewards through epoch 164 (early Conway). Starting at epoch 165 (Conway era, protocol v9), governance actions affect treasury, deposits, and reward calculation. Without governance state tracking, the AdaPot diverges due to proposal deposits, DRep deposits, treasury withdrawals, proposal refunds, and donations.

The goal is to track governance state from on-chain data (proposals, votes, DRep registration/deregistration, committee certs) and derive:
1. Correct DRep distribution (voting power per DRep)
2. Correct proposal status (ratified, expired, enacted)
3. Correct deposit/refund/treasury flows for AdaPot accuracy

All governance code lives inside `ledger-state` as a sub-package (`governance/`). Can be extracted to a separate module later if needed.

### Reference Implementations

| Source | Trust Level | Use For |
|--------|------------|---------|
| Haskell cardano-ledger (`IntersectMBO/cardano-ledger`) | Authoritative | Rules, edge cases, epoch boundary order |
| Amaru Rust node (`pragma-org/amaru`) | High (replicates Haskell bugs explicitly) | v9 bonus logic, backward compatibility comments |
| Yaci-store (`governance-aggr`, `governance-rules`) | Starting point | Port ratification evaluators, DRepExpiryUtil, vote tally |
| Koios preprod API (`preprod.koios.rest/api/v1/`) | Ground truth for verification | DRep distribution, DRep expiry, proposal status |

### Verification Data Sources

- **AdaPot (epochs 5-198)**: `expected_ada_pots_preprod.json` on classpath — existing ground truth
- **AdaPot (epochs 199+)**: Export from yaci-store preprod DB (`SELECT epoch_no, treasury, reserves FROM ada_pots WHERE epoch_no > 198`)
- **Proposal status**: Yaci-store `gov_action_proposal_status` table (reliable)
- **DRep distribution**: Koios preprod API primary (yaci-store has known mismatches with dbsync)
- **DRep expiry**: Koios `drep_info` endpoint (`active_epoch` field)

---

## Conway Governance Rules

### 1. Governance Action Lifecycle

```
Submitted (epoch E, with deposit)
  │
  ├── Voting window: epoch E through E + govActionLifetime (6 epochs mainnet)
  │
  ├── Epoch boundary evaluation:
  │     ├── All thresholds met → RATIFIED
  │     │     └── Enacted at NEXT epoch boundary (1-epoch delay)
  │     │           └── Siblings and descendants REMOVED (deposits refunded)
  │     ├── Past lifetime → EXPIRED (deposit refunded to return address)
  │     └── Otherwise → ACTIVE (continue to next epoch)
  │
  └── Deposit: locked on submission, refunded on expiry/removal
```

**Key details:**
- Proposals submitted in `TransactionBody.proposalProcedures` with deposit (from `ppGovActionDeposit`), return address, anchor, and the gov action
- Each proposal has optional `prevGovActionId` forming a dependency chain per action type
- `expiresAfterEpoch = proposedInEpoch + govActionLifetime`
- Conflict removal: when a proposal is ratified, sibling proposals (same type + same `prevGovActionId`) and their descendants are removed

### 2. Governance Action Types and Required Votes

| Action Type | Committee | DRep | SPO | Notes |
|------------|-----------|------|-----|-------|
| ParameterChange | Required | Required | Required (security group params only) | Thresholds vary by param group (Network, Gov, Technical, Economic) |
| HardForkInitiation | Required | Required | Required | Strictest requirements; "delaying action" |
| TreasuryWithdrawals | Required | Required | Not required | Deducts from treasury |
| NoConfidence | N/A | Required | Required | Committee cannot vote on its own dissolution |
| UpdateCommittee | N/A (or Required if committee exists) | Required | Required | Adds/removes members, updates threshold |
| NewConstitution | Required | Required | Not required | Replaces active constitution |
| InfoAction | None | None | None | No voting required, never ratified/enacted |

### 3. Vote Tallying Rules

#### 3.1 Committee Votes

- Only **non-expired, non-resigned** committee members count
- Members must have authorized hot keys (`AuthCommitteeHotCert`) to vote
- Many-to-one cold-to-hot mapping: a single hot key can represent multiple cold keys
- Threshold: `yes / (yes + no)` >= threshold (abstentions excluded from both numerator and denominator)
- Non-voters implicitly vote "No"
- Members whose term has expired at the evaluation epoch are excluded entirely (not counted as No)

#### 3.2 DRep Votes

- Weighted by **delegated stake** (includes UTXO stake + unwithdraw rewards + proposal deposits)
- Threshold: `yes_stake / (yes_stake + no_stake)` >= threshold
- Abstain stake excluded from both numerator and denominator
- **`AlwaysAbstain` virtual DRep**: votes abstain on everything — its stake excluded from threshold calculation
- **`AlwaysNoConfidence` virtual DRep**: votes YES on `NoConfidence`, NO on everything else
- **Expired/inactive DReps**: their delegated stake does NOT count (excluded entirely, not counted as No or Abstain)
- Unregistered DRep delegations: stake excluded entirely

#### 3.3 SPO Votes

- Weighted by **pool active stake** (from stake distribution)
- Threshold: `yes_stake / (total_active_stake - abstain_stake)` >= threshold
- **Non-voting SPOs for non-HardFork actions**: default to ABSTAIN
- **Non-voting SPOs for HardFork**: default to NO (v10+), ABSTAIN (v9 bootstrap)
- **SPO default vote based on delegated-to DRep** (of the pool's reward account):
  - If delegated to `AlwaysNoConfidence` → votes YES on `NoConfidence`
  - If delegated to `AlwaysAbstain` → votes ABSTAIN
  - Otherwise → NO (default)
- Retiring pools: have zero voting power (excluded from distribution after pool reaping)

### 4. DRep Distribution Calculation

Computed at epoch boundary, for each stake credential delegated to a DRep:

```
drep_voting_stake[drep] += delegated_utxo_stake[cred]
                         + unwithdraw_rewards[cred]
                         + proposal_deposits_by_credential[cred]
```

**Rules:**
- Only include delegations to **REGISTERED** (non-expired) DReps
- Include `ALWAYS_ABSTAIN` and `ALWAYS_NO_CONFIDENCE` virtual DReps (always "registered")
- Proposal deposits: included for the proposer's **staking credential** (the credential from the return address)
- Stake credential must be registered (has active `PREFIX_ACCT` entry)
- In v10+: delegation must have occurred AFTER DRep registration (time-ordering check)
- In v9 (bootstrap): all delegations to registered DReps count regardless of ordering

**Haskell reference:** `DRepPulser` in `Conway/Governance/DRepPulser.hs` — incremental computation over the epoch. Key function: `computeDRepDistr` which aggregates from UMap.

### 5. DRep Expiry Calculation

#### 5.1 Base Formula (v10+)

```
lastActivityEpoch = max(registrationEpoch, lastInteractionEpoch)
activityWindow = drepActivity param at time of last activity
dormantCount = count of dormant epochs in range (lastActivityEpoch, evaluatedEpoch]
expiry = lastActivityEpoch + activityWindow + dormantCount
```

Where `lastInteractionEpoch` = epoch of last vote cast or `UpdateDrepCert` submitted.

#### 5.2 V9 Bonus (bootstrap phase bug)

When `protocolMajorVersion < 10` at time of registration AND no post-registration interactions:

```
v9Bonus = computeV9Bonus(registrationInfo, latestProposalUpToRegistration, dormantEpochsToReg, eraFirstEpoch)
expiry = lastActivityEpoch + activityWindow + dormantCount + v9Bonus
```

**V9 bonus rules (from Haskell behavior, documented in Amaru `backward_compatibility.rs` lines 18-71):**

| Scenario | Bonus |
|----------|-------|
| No proposals submitted before DRep registration | `registeredEpoch - eraFirstEpoch + 1` |
| DRep registered during a dormant gap (after all proposals expired, before new ones) | Length of that dormant gap |
| DRep registered in same epoch as latest proposal AND registration slot <= proposal slot | Length of last continuous dormant period up to registration epoch |
| DRep registered after a proposal is still active | 0 |

**Root cause of the bug:** Haskell updates the dormant epoch counter via `CERTS.updateDormantDRepExpiry` during **transaction processing** (not just at epoch boundaries). So the bonus a DRep receives depends on exactly when in the epoch the registration happened relative to proposal transaction processing. Amaru replicates this by retroactively computing the bonus from tracked proposal history.

#### 5.3 Inactive DRep Recalculation

When `expiry < currentEpoch` (DRep has already expired):
- Walk forward from `lastActivityEpoch + 1`, counting non-dormant epochs
- When `nonDormantCount > activityWindow + v9Bonus`, DRep expired at that epoch - 1
- This finds the exact epoch when the activity window ran out

#### 5.4 Cross-Verification Requirement

DRep expiry is the most edge-case-prone calculation. Our implementation must:
1. Start from yaci-store's `DRepExpiryUtil` (already partially validated)
2. Cross-verify against Amaru's `drep_mandate_calculator` in `governance.rs` (lines 179-363)
3. Verify at runtime against Koios `drep_info` `active_epoch` field for v9-registered DReps
4. Have dedicated unit tests for EACH v9 bonus scenario listed above

### 6. Dormant Epoch Tracking

An epoch is "dormant" if, **after ratification and expiry processing** at the epoch boundary, there are NO remaining active proposals.

**Rules:**
- Tracked as a **set of dormant epoch numbers** (not a simple counter) — needed for range-based counting
- First Conway epoch is always dormant (no proposals exist yet)
- An epoch where proposals were submitted but all got ratified/expired at its boundary IS dormant (no proposals remain)
- The epoch in which a proposal is first submitted is NOT automatically non-dormant — what matters is whether active proposals remain after epoch boundary processing

**Haskell reference:** `numDormantEpochs` in `CertState`, updated by `EPOCH` rule after processing ratifications.

### 7. Enactment Effects

Ratified actions are enacted at the NEXT epoch boundary (1-epoch delay). Effects:

| Action | State Change |
|--------|-------------|
| ParameterChange | Merge `protocolParamUpdate` into future protocol params |
| HardForkInitiation | Update protocol version (triggers era transition) |
| TreasuryWithdrawals | Deduct from treasury, credit each withdrawal address as reward |
| NoConfidence | Clear committee entirely (`committee = Nothing`) |
| UpdateCommittee | Remove specified members, add new members with term epochs, update threshold |
| NewConstitution | Replace active constitution (anchor + optional script hash) |
| InfoAction | No-op (never actually ratified) |

**"Delaying action":** When a `HardForkInitiation` is ratified, all other active proposals get their lifetime extended by 1 epoch. This is tracked via `rsDelayed` flag in `RatifyState`.

### 8. Deposit and Treasury Flows

#### 8.1 Proposal Deposits
- **Lock**: On submission, `ppGovActionDeposit` lovelace locked from submitter
- **Refund on expiry/removal**: Credited to `pProcReturnAddr` (proposal's return address)
  - If return address is registered stake credential → added as reward (spendable next epoch)
  - If return address is NOT registered → unclaimed, goes to treasury
- **Counts toward DRep voting stake**: Proposer's staking credential's proposal deposits add to their DRep's voting power

#### 8.2 DRep Deposits
- **Lock**: On `RegDrepCert`, `ppDRepDeposit` lovelace locked
- **Refund**: On `UnregDrepCert`, deposit returned to DRep's reward account
- Processed at epoch boundary (not immediately on cert processing)

#### 8.3 Treasury Withdrawals
- From ratified `TreasuryWithdrawalsAction`
- Each withdrawal: (reward_account, amount)
- Deducted from treasury, credited to reward accounts
- If address not registered → unclaimed, goes back to treasury

#### 8.4 Donations
- `TransactionBody.donation` field (Conway era)
- Added directly to treasury at epoch boundary

#### 8.5 AdaPot Impact Summary

At epoch boundary (governance effects on AdaPot):
```
treasury += donations
treasury -= sum(treasuryWithdrawals)        # from enacted actions
treasury += unclaimed_refunds               # refunds to unregistered addresses
deposits -= sum(proposalDepositRefunds)     # expired/removed proposals
deposits -= sum(drepDepositRefunds)         # deregistered DReps
deposits += sum(newProposalDeposits)        # from blocks in this epoch
deposits += sum(newDrepDeposits)            # from blocks in this epoch
```

These changes must be applied BEFORE reward calculation to ensure correct treasury/reserves values.

---

## Bootstrap Phase (Protocol v9) — Specifics and Known Bugs

### Restrictions During Bootstrap

1. **DRep thresholds = 0**: All DRep voting thresholds become 0, meaning DRep approval is automatic for ALL action types
   - Haskell: `votingDRepThresholdInternal` returns 0 when `bootstrapPhase`
   - Ratification still requires committee + SPO votes where applicable

2. **Committee minimum size bypassed**: Committee voting proceeds even with fewer non-expired, non-resigned members than `ppCommitteeMinSize`
   - Haskell: `committeeAccepted` skips minSize check during bootstrap

3. **SPO HardFork voting relaxed**: Non-voting SPOs counted as ABSTAIN (not NO)
   - Makes hard fork initiation easier during bootstrap
   - Post-bootstrap: non-voting SPOs count as NO for HardFork (much stricter)

4. **Certain proposals restricted**: `DisallowedProposalDuringBootstrap` predicate failure
   - Check which specific action types are disallowed during bootstrap

5. **DRep votes not collected**: In bootstrap phase, DRep voting data is effectively ignored since all thresholds are 0
   - Yaci-store: `VotingDataCollector` returns empty DRepVotes during bootstrap

### Known Bug: DRep Expiry V9 Bonus

See Section 5.2 above for full details. Summary:
- DReps registered during dormant periods get bonus epochs added to their expiry
- The bonus depends on exact registration timing relative to proposal submissions
- This is a Haskell implementation artifact that Amaru explicitly replicates
- Must be replicated exactly for DRep distribution correctness

### Known Bug: Previous Deregistration Tracking

- In v9, re-registering a DRep after deregistration requires tracking the `previous_deregistration` CertificatePointer
- Affects the v9 bonus calculation: re-registered DReps may or may not get the bonus depending on their previous deregistration timing
- Amaru tracks this via `DRepState.previous_deregistration: Option<CertificatePointer>`
- Can be dropped when bootstrapping from v10+ snapshots (only relevant during v9)

### V9 → V10 Transition

- Triggered by ratified `HardForkInitiation` action during v9
- `max_bootstrap_phase_epoch`: the last epoch of protocol v9 (epoch of the HardFork ratification)
- After transition: DRep thresholds become real values, SPO HardFork voting becomes strict, committee min size enforced
- V10 delegation filtering: `delegation.slot > drep.registrationSlot` check applies (time-ordering)
- Delegations made during v9 to DReps that were registered during v9 remain valid in v10 (grandfather clause via `max_bootstrap_phase_epoch` check)

---

## Epoch Boundary Processing Order (Conway)

Must match Haskell ledger EXACTLY. Order from `Conway/Rules/Epoch.hs` and `Conway/Rules/NewEpoch.hs`:

```
Phase 1 — PreEpochTransitionEvent (epoch N-1 → N):
  1.  Finalize protocol parameters for epoch N
  2.  Bootstrap AdaPot if needed
  3.  Complete DRep distribution calculation (DRepPulser)
  4.  RATIFY: Evaluate all active proposals against vote thresholds
  5.  ENACT: Apply ratified actions (param changes, committee updates, treasury withdrawals)
  6.  Remove expired proposals + siblings/descendants of ratified proposals
  7.  Refund proposal deposits (expired + removed) → reward accounts or treasury
  8.  Refund DRep deposits (deregistered DReps)
  9.  Update DRep expiry (account for dormant epochs)
  10. Update dormant epoch tracking (any active proposals remaining?)
  11. Add epoch donations to treasury
  12. Calculate and distribute rewards (uses correct treasury/deposits from above)

Phase 2 — EpochTransitionEvent:
  13. SNAP: Take delegation/stake snapshot

Phase 3 — PostEpochTransitionEvent:
  14. POOLREAP: Pool deposit refunds for retired pools
```

Steps 3-11 are NEW governance additions. Steps 1-2 and 12-14 are existing.

---

## Implementation Design

### Package Structure

```
ledger-state/src/main/java/com/bloxbean/cardano/yaci/node/ledgerstate/governance/
├── GovernanceStateStore.java          # RocksDB CRUD for all governance state
├── GovernanceCborCodec.java           # CBOR encode/decode for governance records
├── GovernanceBlockProcessor.java      # Block-level governance action extraction
├── epoch/
│   ├── GovernanceEpochProcessor.java  # Epoch boundary orchestrator
│   ├── DRepDistributionCalculator.java # DRep stake distribution
│   └── DRepExpiryCalculator.java      # DRep expiry (port of DRepExpiryUtil + cross-verify)
├── ratification/
│   ├── RatificationEngine.java        # Core ratification logic
│   ├── VoteTallyCalculator.java       # Vote tally (committee, DRep, SPO)
│   ├── EnactmentProcessor.java        # Apply ratified action effects
│   └── ProposalDropService.java       # Sibling/descendant proposal removal
└── model/
    ├── GovActionRecord.java           # Stored proposal state
    ├── DRepStateRecord.java           # Stored DRep state
    ├── CommitteeMemberRecord.java     # Stored committee member
    ├── GovernanceSnapshot.java        # Governance params snapshot
    └── RatificationResult.java        # Proposal evaluation result
```

### RocksDB Storage Schema

New prefixes in existing `cfState` column family (existing go up to 0x55):

| Prefix | Key Layout | Value (CBOR) | Description |
|--------|-----------|--------------|-------------|
| `0x60` | `txHash(32) + govIdx(2 BE)` | GovActionRecord | Active proposals |
| `0x61` | `txHash(32) + govIdx(2 BE) + voterType(1) + voterHash(28)` | Vote byte (0=NO,1=YES,2=ABSTAIN) | Votes per proposal |
| `0x62` | `credType(1) + credHash(28)` | DRepStateRecord | DRep registration state |
| `0x63` | `credType(1) + coldHash(28)` | CommitteeMemberRecord | Committee member state |
| `0x64` | (singleton) | Constitution CBOR | Active constitution |
| `0x65` | (singleton) | dormant epoch set CBOR | Set of dormant epoch numbers |
| `0x66` | `epoch(4 BE) + credType(1) + drepHash(28)` | stake (uint) | DRep distribution snapshot |
| `0x67` | `epoch(4 BE)` | bool | Epoch had active proposals flag |
| `0x68` | `epoch(4 BE)` | donations (uint) | Per-epoch donation accumulator |
| `0x69` | `actionType(1)` | txHash(32) + govIdx(2) | Last enacted action per type |
| `0x6A` | `epoch(4 BE) + txHash(32) + govIdx(2 BE)` | empty | Proposals ratified in epoch |

**GovActionRecord CBOR:**
```
{0: deposit(uint), 1: returnAddress(bstr), 2: proposedInEpoch(uint),
 3: expiresAfterEpoch(uint), 4: actionType(uint), 5: prevActionTxHash(bstr),
 6: prevActionIndex(uint), 7: govActionCbor(bstr), 8: proposalSlot(uint)}
```

**DRepStateRecord CBOR:**
```
{0: deposit(uint), 1: anchorUrl(tstr), 2: anchorHash(bstr),
 3: registeredAtEpoch(uint), 4: lastInteractionEpoch(uint),
 5: expiryEpoch(uint), 6: isActive(uint), 7: registeredAtSlot(uint),
 8: protocolVersionAtRegistration(uint)}
```

**CommitteeMemberRecord CBOR:**
```
{0: hotCredType(uint), 1: hotHash(bstr), 2: expiryEpoch(uint), 3: isResigned(uint)}
```

### Key Classes

#### `GovernanceStateStore`
- CRUD for proposals, votes, DReps, committee, constitution, dormant epochs
- RocksDB prefix-scan iteration for all active proposals, votes per proposal, all DReps
- Delta journal integration via `DeltaOp` list (same pattern as `DefaultAccountStateStore`)
- Conway genesis bootstrap (initial committee, constitution, governance params)

#### `GovernanceBlockProcessor`
Called from `DefaultAccountStateStore.applyBlock()`:
- Extract proposal submissions → store as active proposals with deposit
- Extract votes → store/update vote records
- Extract DRep certs → write `DRepStateRecord` (richer than existing `PREFIX_DREP_REG`)
- Extract committee certs → write `CommitteeMemberRecord`
- Accumulate donations per epoch
- Track DRep `lastInteractionEpoch` on votes and `UpdateDrepCert`

#### `GovernanceEpochProcessor`
Epoch boundary orchestrator — called from `EpochBoundaryProcessor.processEpochBoundary()`:

```java
GovernanceEpochResult processEpochBoundary(int previousEpoch, int newEpoch) {
    // 1. Calculate DRep distribution
    Map<DRepId, BigInteger> drepDist = drepDistCalculator.calculate(previousEpoch);
    // 2. RATIFY
    List<RatificationResult> results = ratificationEngine.evaluate(...);
    // 3. ENACT ratified actions
    BigInteger treasuryDelta = enactAll(ratified);
    // 4. Remove expired + conflicting proposals
    proposalDropService.dropExpiredAndConflicting(results, previousEpoch);
    // 5. Refund deposits
    BigInteger depositRefunds = refundDeposits(expiredAndDropped);
    // 6. Update DRep expiry
    drepExpiryCalculator.updateAll(newEpoch, dormantEpochs);
    // 7. Update dormant tracking
    updateDormantTracking(newEpoch);
    // 8. Process donations
    BigInteger donations = governanceStore.getEpochDonations(previousEpoch);
    return new GovernanceEpochResult(treasuryDelta, depositRefunds, donations);
}
```

#### `RatificationEngine`
Inlined from yaci-store `governance-rules` (no dependency on yaci-store):
- Sort proposals by priority: HardFork > UpdateCommittee > NoConfidence > NewConstitution > ParameterChange > TreasuryWithdrawals > InfoAction
- Evaluate committee + DRep + SPO thresholds per action type
- Handle bootstrap phase: DRep thresholds = 0, committee min size bypassed
- Handle "delaying action" flag
- Return RATIFIED / EXPIRED / ACTIVE per proposal

#### `DRepExpiryCalculator`
Port from yaci-store `DRepExpiryUtil`, cross-verified against:
1. Amaru `backward_compatibility.rs` (lines 18-71) — detailed v9 bug documentation
2. Amaru `drep_mandate_calculator` in `governance.rs` (lines 179-363) — full algorithm
3. Haskell `CERTS.updateDormantDRepExpiry` — authoritative source

### Integration Points

#### 1. `DefaultAccountStateStore.applyBlock()` — block processing
```java
// After existing certificate processing:
if (governanceBlockProcessor != null) {
    governanceBlockProcessor.processBlock(block, slot, currentEpoch, batch, deltaOps);
}
```
DRep certs get dual-write (existing `PREFIX_DREP_REG` + governance `PREFIX 0x62`).

#### 2. `EpochBoundaryProcessor.processEpochBoundary()` — epoch transition
Insert governance processing BEFORE reward calculation (between step 2 and step 3 of current code).

#### 3. `AdaPotTracker` — treasury/deposit tracking
- Governance deposits tracked in AdaPot `deposits` field (currently hardcoded to `BigInteger.ZERO`)
- Treasury donations and withdrawals applied at epoch boundary

#### 4. Rollback
All governance writes use `WriteBatch` + `DeltaOp`. Existing `rollbackTo()` reverses automatically. Epoch-scoped data (DRep distribution, dormant tracking) needs cleanup for epochs beyond rollback target.

### Code to Port from Existing Sources

| Component | Source | Approach |
|-----------|--------|----------|
| `DRepExpiryUtil` | yaci-store `governance-aggr` | Port + cross-verify with Amaru/Haskell |
| Vote tally logic | yaci-store `governance-rules/voting/` | Port, adapt for RocksDB data access |
| Ratification evaluators | yaci-store `governance-rules/ratification/impl/` | Port all 7 evaluators |
| `ProposalDropService` | yaci-store `governance-rules/service/` | Port sibling/descendant logic |
| DRep dist calculation | yaci-store `governance-aggr/service/DRepDistService` | Rewrite for RocksDB (SQL→prefix scan) |
| Core governance models | yaci `core/model/governance/` | Use directly (already exist) |

---

## Phased Implementation

### Phase 1: Storage Foundation
- Create `governance/model/*.java` record types
- Create `governance/GovernanceCborCodec.java`
- Create `governance/GovernanceStateStore.java` with full CRUD + delta ops
- Unit tests for CBOR round-trip and store CRUD

### Phase 2: Block Processing
- Create `governance/GovernanceBlockProcessor.java`
- Modify `DefaultAccountStateStore.applyBlock()` — wire governance processor
- Modify `DefaultAccountStateStoreProvider.java` — initialization
- Extend `EpochParamProvider` / `EpochParamTracker` with governance params
- Verify: sync through Conway blocks, inspect stored proposals/votes/DReps

### Phase 3: DRep Distribution + Expiry
- Create `governance/epoch/DRepDistributionCalculator.java`
- Create `governance/epoch/DRepExpiryCalculator.java`
- Dedicated unit tests for each v9 bonus edge case
- Verify DRep distribution against Koios preprod API
- Verify DRep expiry against Koios `drep_info` `active_epoch`

### Phase 4: Ratification + Enactment
- Create all `governance/ratification/*.java` classes
- Unit tests per action type, bootstrap and post-bootstrap configs
- Verify proposal statuses against yaci-store `gov_action_proposal_status`

### Phase 5: Epoch Integration + AdaPot
- Create `governance/epoch/GovernanceEpochProcessor.java`
- Modify `EpochBoundaryProcessor.java` — integrate governance before rewards
- Modify `AdaPotTracker.java` — governance deposits/treasury changes
- Export updated AdaPot values from yaci-store DB for epochs 199+
- Verify: AdaPot matches through Conway epochs (epoch 165+)

### Phase 6: Edge Cases + Full Verification
- Conway genesis bootstrap (initial committee, constitution from genesis)
- Bootstrap phase v9 bonus edge cases — verify against Koios
- `previous_deregistration` tracking for re-registration
- Rollback cleanup for governance epoch state
- Full sync: preprod epochs 163-latest with AdaPot matching
- DRep distribution spot-checks against Koios at each epoch boundary

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Treasury/deposit off by 1 lovelace | AdaPot fails, rewards compound errors | `System.exit(1)` on mismatch; verify every epoch |
| Proposal ordering wrong | Different ratification outcomes (delaying action) | Use yaci-store's `GovActionPriority` ordering, verify against Haskell test vectors |
| DRep expiry v9 bonus edge case | Wrong DRep distribution → wrong vote tallies | Cross-verify with Amaru + Koios; dedicated test per scenario |
| Performance at scale (mainnet) | Slow epoch boundary processing | RocksDB prefix scans are efficient; batch DRep distribution if needed |
| Conway genesis state wrong | All subsequent governance state wrong | Parse from Conway genesis JSON (available in node-runtime config) |

---

## Implementation Progress

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: Storage | Done | Model records (4), CBOR codec, GovernanceStateStore. DeltaOp shared via public. Committee threshold (0x6B). previousDeregistrationSlot added. |
| Phase 2: Block Processing | Done | GovernanceBlockProcessor wired into applyBlock(). Dual-write for 5 cert types. EpochParamTracker: Conway governance params added, applyEnactedParamChange() for governance-driven param updates, removed broken processTransaction() Conway code. EpochParamProvider: 6 governance param defaults added. GovernanceStateStore: getActiveDRepStates() filter. Committee placeholder uses MAX_VALUE expiry. |
| Phase 3: DRep Dist + Expiry | Done | DRepExpiryCalculator (v9/v10, v9Bonus, inactive recalc). DRepDistributionCalculator (iterates DREP_DELEG, snapshot stake + rewards + proposal deposits, virtual DReps). Made AccountStateCborCodec decode methods public. |
| Phase 4: Ratification | Done | VoteTallyCalculator (committee/DRep/SPO tallies + threshold checks), RatificationEngine (7 evaluators, priority ordering, delaying actions, prev-action validation), ProposalDropService (siblings/descendants BFS), EnactmentProcessor (NoConfidence clears committee, ParameterChange/HardFork delegated to GovernanceEpochProcessor). V9 previousDeregistrationSlot check added to DRepDistributionCalculator. |
| Phase 5: Epoch Integration | Done | GovernanceEpochProcessor wired into EpochBoundaryProcessor. Treasury/deposit deltas fed to reward calc via govResult. Deposit refunds credited to return addresses via RewardCreditor. Treasury withdrawals credited to recipients. Unclaimed refunds→treasury. PoolStakeResolver with typed PoolStakeData record. AdaPotTracker provides treasury input. processEpochBoundaryAndCommit() self-manages WriteBatch. GovAction stored as CBOR via GovActionSerializer.serializeDI(). UpdateSerializer.serializePPUpdate() (all 34 fields). CborSerializationUtil: serializeRational, cborUInt, cborMapPut helpers. |
| Phase 6: Edge Cases | Done | **EPOCHS 5-198 ALL PASS.** Conway genesis bootstrap (7 committee members, constitution, threshold). Reward_rest deferred credit for proposal deposit refunds. Pointer address fix (paramTracker for Conway detection). UTXO snapshot with slot filter. Stale delegation fix (dereg-after-deleg). Committee tally: members without hot keys excluded. AuthCommitteeHotCert: no placeholders for unknown members. EpochParamTracker persists to `epoch_params` CF. Virtual DRep hex fix. |
