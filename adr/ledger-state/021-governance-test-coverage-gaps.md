# ADR-021: Governance Test Coverage Gaps

**Status:** Proposed
**Date:** 2026-04-03
**Authors:** Satya
**Depends on:** ADR-020 (Conway-Era Governance State Tracking)

## Context

The ledger-state governance subsystem was verified correct through epoch 280 on preprod with:
- 10,153 individual DRep distribution entries matched against DBSync (zero mismatches)
- 121 epochs of AdaPot (treasury + reserves) exact match
- 86 proposal ratification/expiry events at correct epochs
- 121 epoch stake delegation counts matched

110 unit tests cover the critical paths. However, several production classes lack direct unit tests. This ADR documents the gaps for future work.

## Current Test Coverage (110 tests)

| Class | Tests | Type |
|-------|-------|------|
| RatificationEngine | 23 | Stateless |
| InMemoryAccountStateStoreTest | 17 | In-memory |
| ProtocolParamGroupClassifier | 13 | Pure logic |
| GovernanceStateStore | 10 | RocksDB |
| ProposalDropService | 8 | Pure logic |
| EnactmentProcessor | 8 | RocksDB |
| DRepExpiryCalculator | 7 | Pure logic |
| DRepDistributionCalculator | 7 | RocksDB |
| VoteTallyCalculator | 5 | Pure logic |
| GovernanceBlockProcessor | 5 | RocksDB |
| StoreRewardRest | 5 | RocksDB |
| GovernanceTwoPhaseCommit | 2 | RocksDB |

All 4 bugs fixed in the April 2026 session have direct regression tests.

## Gaps to Address

### 1. GovernanceEpochProcessor — Full Orchestration (CRITICAL)

**What's missing:** End-to-end epoch boundary test that exercises the complete flow: DRep distribution → ratification → enactment → deposit refunds → dormant tracking → DRep expiry.

**Why it matters:** This is the orchestrator that coordinates all governance subsystems. The two-phase commit is tested at the store level, but the full ordering (Phase 1 enact → commit → Phase 2 ratify) with real DRep distribution and ratification is not tested as a unit.

**Suggested approach:** 
- Create `GovernanceEpochProcessorTest.java` with real RocksDB
- Scenario 1: Bootstrap → first Conway epoch (163) with genesis committee
- Scenario 2: Two-phase commit with real proposal ratification (reproduce epochs 231-232)
- Scenario 3: Mass expiry with deposit refunds (reproduce epochs 234-236)
- Data source: Extract fixtures from DBSync or parquet exports

**Complexity:** HIGH — requires wiring DRepDistributionCalculator, RatificationEngine, EnactmentProcessor, ProposalDropService, VoteTallyCalculator, DRepExpiryCalculator, and AdaPotTracker together with real RocksDB data.

### 2. GovernanceBlockProcessor — Proposal/Vote Processing (HIGH)

**What's missing:** Tests for proposal submission processing, vote recording, DRep interaction tracking, and committee hot key authorization.

**Currently covered:** Only donation accumulation (5 tests). The proposal/vote/cert processing paths are untested.

**Suggested approach:**
- Test `processProposals()`: submit a ParameterChange proposal → verify stored in GovernanceStateStore
- Test `processVotesAndTrackInteractions()`: cast DRep vote → verify vote stored + DRep lastInteractionEpoch updated
- Test `processCommitteeHotKeyAuth()`: authorize hot key for non-member → verify placeholder stored (the bug fix)
- Test `processCommitteeResignation()`: resign member → verify marked as resigned
- Test `processDRepRegistration/Deregistration/Update`: full DRep lifecycle

**Complexity:** MEDIUM — needs Block/TransactionBody builders with governance fields (ProposalProcedure, VotingProcedures, certs).

### 3. EpochBoundaryProcessor — Reward + Governance Orchestration (HIGH)

**What's missing:** End-to-end test of the full epoch boundary sequence: param finalization → reward calculation → delegation snapshot → POOLREAP → governance → AdaPot verification.

**Why it matters:** The ordering of these steps is critical — wrong order causes cascading errors (e.g., POOLREAP before snapshot inflates active stake).

**Currently covered:** Indirectly via full sync (121 AdaPot matches), but no unit test.

**Suggested approach:** 
- Requires mocking or stubbing EpochRewardCalculator (depends on cf-rewards library)
- Focus on verifying the ordering: governance delta applied AFTER reward calculation
- Verify AdaPot verification catches treasury/reserves mismatches

**Complexity:** HIGH — many dependencies, cf-rewards library interaction.

### 4. EpochRewardCalculator (MEDIUM)

**What's missing:** Direct unit tests for reward distribution logic, pool deposit refunds, leader/member reward splits.

**Currently covered:** 121 epoch AdaPot matches validate the output is correct.

**Suggested approach:**
- Extract reward calculation inputs for a specific epoch from parquet exports
- Run EpochRewardCalculator with those inputs
- Verify output matches expected rewards from DBSync `reward` table

**Complexity:** MEDIUM — requires cf-rewards library configuration and epoch stake data.

### 5. DefaultAccountStateStore — RocksDB Integration (MEDIUM)

**What's missing:** The existing InMemoryAccountStateStoreTest (17 tests) covers the in-memory implementation but NOT the RocksDB-backed DefaultAccountStateStore. Key differences: WriteBatch visibility, CBOR encoding/decoding, prefix-based iteration, delta journaling.

**Suggested approach:**
- Port the 17 InMemoryAccountStateStoreTest scenarios to use TestRocksDBHelper + DefaultAccountStateStore
- Add tests for: stale delegation detection, reward_rest aggregation, snapshot creation

**Complexity:** MEDIUM — TestRocksDBHelper infrastructure already exists.

### 6. GovernanceCborCodec (LOW)

**What's missing:** Direct encode/decode roundtrip tests for all governance record types.

**Currently covered:** Implicitly tested via GovernanceStateStore tests (data survives store → retrieve cycle).

**Suggested approach:** Parameterized tests with edge cases (null fields, max values, empty collections).

**Complexity:** LOW.

## Verification Infrastructure

The `verify.sh` script and parquet export system provide a second layer of verification:
- `./verify.sh` compares parquet exports against DBSync and Yaci-Store
- Covers: AdaPot, individual DRep distribution, epoch stake, proposal ratification
- Run after any governance code change to catch integration regressions

## Priority Order

1. GovernanceEpochProcessor full orchestration (highest risk)
2. GovernanceBlockProcessor proposal/vote processing
3. DefaultAccountStateStore RocksDB port of in-memory tests
4. EpochBoundaryProcessor ordering verification
5. EpochRewardCalculator with real data
6. GovernanceCborCodec roundtrip tests
