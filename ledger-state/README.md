# ledger-state — Cardano Ledger State Implementation

This module implements Cardano ledger state tracking for the Yaci node, covering:
- Stake registration/deregistration
- Pool and DRep delegation
- Epoch delegation snapshots (mark snapshot)
- Reward calculation (via [cf-java-rewards-calculation](https://github.com/cardano-foundation/cf-java-rewards-calculation))
- AdaPot (treasury/reserves) tracking
- Protocol parameter tracking

## Epoch Boundary Processing (EPOCH Rule)

At each epoch boundary (first block of a new epoch), three events fire in sequence,
mirroring the Cardano ledger spec's **EPOCH rule** (shelley-ledger.pdf §17.4):

```
PreEpochTransitionEvent   →  Reward calculation, AdaPot update, protocol param finalization
EpochTransitionEvent      →  SNAP: delegation/stake mark snapshot
PostEpochTransitionEvent  →  POOLREAP: pool deposit refunds for retiring pools
```

All three events complete before `BlockAppliedEvent` for the first block of the new epoch.

### Why three phases?

The Cardano ledger spec defines the EPOCH rule as three sub-rules applied in order:

1. **Reward distribution** must happen before SNAP so that distributed rewards are
   included in the snapshot's stake balances.
2. **SNAP** must happen before POOLREAP so that pool deposit refunds (500 ADA per
   retired pool) do not inflate the snapshot's active stake.
3. **POOLREAP** credits deposit refunds to reward accounts after the snapshot is frozen.

## Snapshot Epoch Convention

**Key design decision**: Snapshot epoch labels match the
[yaci-store](https://github.com/bloxbean/yaci-store) `epoch_stake` convention.

| Convention | Meaning of `snapshot[E]` |
|-----------|--------------------------|
| **yaci (this module)** | Delegation/stake state at the **END** of epoch E |
| **yaci-store `epoch_stake`** | Same — state at the **END** of epoch E |

The snapshot for epoch E is physically taken at the epoch E→E+1 boundary (when the
first block of epoch E+1 arrives). At that point, all blocks from epoch E have been
processed but no blocks from epoch E+1 have been applied yet.

### Reward calculation snapshot lookup

For reward epoch N, the Cardano ledger uses the **mark snapshot from the END of
epoch N-4**. With our labeling convention (key E = end of epoch E):

```
reward epoch N
  └─ stakeEpoch = N - 2
       └─ snapshotKey = stakeEpoch - 2 = N - 4
            └─ snapshot[N-4] = state at END of epoch N-4
```

This matches DBSync/Haskell node ground truth (verified on preprod).

**Why N-4 and not N-2?** The mark snapshot is taken during epoch N-3's epoch boundary
processing. At the N-4→N-3 boundary, the snapshot captures end-of-epoch-N-4 delegation
state. That snapshot is then used 2 epochs later for reward calculation at epoch N.

### Comparing with yaci-store

When comparing yaci's snapshot with yaci-store's `epoch_stake`:

```python
# Both use the same epoch convention now — compare directly
yaci_snapshot = fetch(f"/api/debug/epoch-snapshot/{epoch}")
store_epoch_stake = query("SELECT ... FROM epoch_stake WHERE epoch = %s", epoch)
```

For reward input comparison at reward epoch N:
```python
# Stake snapshot used by reward calc: epoch N-2
compare_epoch_snapshot(yaci_url, store, epoch=reward_epoch - 2)
```

## Stake Deregistration Behavior

**Key design decision**: Stake deregistration **completely removes** the account entry,
including pool and DRep delegations — matching the Haskell ledger implementation.

Per Haskell source (`Deleg.hs` → `unregisterShelleyAccount`): deregistration uses
`Map.extract` to fully remove the `ShelleyAccountState` entry from `dsAccounts`.
The `sasStakePoolDelegation` field is discarded. Re-registration via `registerShelleyAccount`
creates a fresh entry with `sasStakePoolDelegation = SNothing` (no delegation).

This matches:
- **Haskell node**: `Map.extract` removes entire entry; `unDelegReDelegStakePool` clears
  the reverse index (`spsDelegators`) on the pool
- **yaci-store**: The `delegation` table retains historical entries, but the snapshot query
  uses a LEFT JOIN on `stake_registration` to exclude credentials with deregistration
  after their latest delegation — effectively excluding re-registered-but-undelegated credentials
- **DBSync**: Matches Haskell node behavior (ground truth for `expected_ada_pots`)

### Pool Retirement vs Deregistration

| Action | Account entry | Pool delegation | Re-delegation needed? |
|--------|--------------|-----------------|----------------------|
| **Stake deregistration** | Removed entirely | Discarded | Yes (after re-registration) |
| **Pool retirement (POOLREAP)** | Preserved | Set to Nothing | Yes |

## Storage

- **RocksDB** (`DefaultAccountStateStore`): Production persistence with column families
  for state, epoch snapshots, deltas, and replay
- **In-memory** (`InMemoryAccountStateStore`): Testing and development

### Key prefixes (RocksDB)

| Prefix | Data |
|--------|------|
| `0x01` | Stake account (registration + reward balance + deposit) |
| `0x02` | Pool delegation |
| `0x03` | DRep delegation |
| `0x10` | Pool deposit |
| `0x11` | Pool retirement |
| `0x50` | Pool block count (epoch-scoped) |
| `0x51` | Epoch fees |
| `0x52` | AdaPot (treasury/reserves per epoch) |

### Epoch snapshot column family

Snapshot keys: `[epoch(4 bytes BE)][credType(1)][credHash(28)]`
Snapshot values: CBOR-encoded `{poolHash, stakeAmount}`

## Reward Calculation — Input/Output Equivalence

The reward calculation uses [cf-java-rewards-calculation](https://github.com/cardano-foundation/cf-java-rewards-calculation),
the same library as yaci-store. It is **deterministic**: identical inputs produce identical
outputs. Therefore, any treasury/reserves mismatch implies at least one input differs.

### Input mapping (reward epoch N)

| Input | yaci (this module) | yaci-store | Epoch ref |
|-------|-------------------|------------|-----------|
| Treasury/reserves | `AdaPotTracker.getAdaPot(N-1)` | `AdaPotStorage.findByEpoch(N-1)` | N-1 |
| Protocol params | `EpochParamProvider.get*(N-2)` | `ProtocolParamService.getProtocolParam(N-2)` | N-2 |
| Block counts | `getPoolBlockCounts(N-2)` | `BlockInfoService.getPoolBlockCount(N-2)` | N-2 |
| Epoch fees | `getEpochFees(N-2)` | `EpochInfoService` → `AdaPot(N-1).fees` | N-2 |
| Active stake | Sum of `snapshot[N-2]` amounts | `epoch_stake` sum for epoch N-2 | N-2 |
| Pool states | Built from `snapshot[N-2]` + pool params | `PoolStateService` from `epoch_stake` + pool details | N-2 |
| Retired pools | `PREFIX_POOL_RETIRE` where epoch=N | `PoolStorage.findRetiredPools(N)` | N |
| Deregistered | Stake events in slot range `[feeEpochStart, feeEpochEnd)` | `StakeRegistrationService` filtered by slot | N-1 |
| MIR certificates | (not yet implemented) | `RewardStorageReader` instant rewards | N-1 |

### Verification status (preprod)

| Epoch range | AdaPot (treasury/reserves) | Notes |
|------------|---------------------------|-------|
| 5-33 | EXACT MATCH | Verified against DBSync ground truth (System.exit on mismatch) |
| 34 | ~8.9 ADA treasury diff | 1 extra delegation entry vs store (mid-epoch registration timing) |

**Root cause of epoch 30+ mismatch**: The `registeredSinceLast` and `registeredUntilNow`
account sets differ between yaci and yaci-store. These sets determine which pool operators
receive their leader rewards vs having rewards redirected to treasury:

- The treasury/reserves diff at each epoch is **exactly symmetric** (treasury increases by
  the same amount reserves decreases), confirming it's a reward distribution difference
  not a total reward pot difference.
- All scalar inputs match: treasury, reserves, fees, block counts, active stake, per-pool
  stake, retired pools. The reward pot itself is computed identically.
- Three pool operators at epoch 30 are denied rewards because their reward address
  (`acd71b...`) is not in `registeredSinceLast`. Both yaci and store agree these are
  denied — but the total denied amount differs because the `registeredSinceLast` set
  size differs (yaci=43 members vs store value unknown), causing different member reward
  distributions.

**Investigation findings (epoch 30 deep dive)**:

All scalar inputs match exactly between yaci and store: treasury/reserves (epoch 29),
fees, block counts, total active stake, pool block counts, retired pools,
`registeredSinceLast` (43 in both), `registeredUntilNow`, deregistered sets, MIR (empty).
No treasury donations or withdrawals on preprod at this epoch.

**Fixes applied** (reduced epoch 34 diff from ~616 ADA to ~8.9 ADA):

1. **Retired pool filtering** (FIXED): `createDelegationSnapshot()` now excludes delegations
   to pools with `retireEpoch <= snapshotEpoch`. Delegators to retired pools (e.g. pool
   `2bdbd0...` retiring at epoch 30) were inflating the snapshot active stake by ~1T lovelace.
2. **Zero-balance delegators included** (FIXED): Removed the `stakeAmount == 0` skip. Store
   includes zero-balance entries in `epoch_stake`, and they may be pool owners.
3. **~8.9 ADA diff at epoch 34 (FIXED)**: Credential `f221441c` follows register →
   delegate → deregister → re-register within epoch 30. Per Haskell ledger, deregistration
   removes the delegation entirely (`Map.extract`). Re-registration starts fresh with no
   delegation. The credential is undelegated at the epoch boundary despite being registered.
   Fix: reverted to deleting delegations on deregistration (matching Haskell behavior).
