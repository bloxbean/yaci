package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.DrepType;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory account state store for testing and non-RocksDB configurations.
 * Uses ConcurrentHashMap with hex string keys (same pattern as InMemoryChainState).
 */
public class InMemoryAccountStateStore implements AccountStateStore {

    // Key format: "prefix:credType:credHash" for accounts/delegations
    private final ConcurrentHashMap<String, StakeAccountEntry> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PoolDelegEntry> poolDelegations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DRepDelegEntry> drepDelegations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigInteger> poolDeposits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> poolRetirements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigInteger> drepRegistrations = new ConcurrentHashMap<>(); // key → deposit
    private final ConcurrentHashMap<String, String> committeeHotKeys = new ConcurrentHashMap<>(); // coldKey → hotHash
    private final ConcurrentHashMap<String, Boolean> committeeResignations = new ConcurrentHashMap<>(); // coldKey → true
    private final ConcurrentHashMap<String, BigInteger> mirRewards = new ConcurrentHashMap<>(); // credKey → accumulated reward
    // Epoch delegation snapshots: key = "epoch:credType:credHash", value = poolHash
    private final ConcurrentHashMap<String, String> epochDelegSnapshots = new ConcurrentHashMap<>();
    private volatile int lastSnapshotEpoch = -1;
    private static final int SNAPSHOT_RETENTION_EPOCHS = 5;
    private volatile BigInteger mirPotToReserves = BigInteger.ZERO;
    private volatile BigInteger mirPotToTreasury = BigInteger.ZERO;
    private volatile BigInteger totalDeposited = BigInteger.ZERO;

    // Delta stack for rollback
    private final Deque<BlockDelta> deltaStack = new ArrayDeque<>();

    private final boolean enabled;
    private final EpochParamProvider epochParamProvider;

    record StakeAccountEntry(BigInteger reward, BigInteger deposit) {}
    record PoolDelegEntry(String poolHash, long slot, int txIdx, int certIdx) {}
    record DRepDelegEntry(int drepType, String drepHash, long slot, int txIdx, int certIdx) {}

    private static final EpochParamProvider ZERO_PROVIDER = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
    };

    public InMemoryAccountStateStore() {
        this(true, ZERO_PROVIDER);
    }

    public InMemoryAccountStateStore(boolean enabled) {
        this(enabled, ZERO_PROVIDER);
    }

    public InMemoryAccountStateStore(boolean enabled, EpochParamProvider epochParamProvider) {
        this.enabled = enabled;
        this.epochParamProvider = epochParamProvider != null ? epochParamProvider : ZERO_PROVIDER;
    }

    private static String credKey(int credType, String credHash) {
        return credType + ":" + credHash;
    }

    private static int credTypeInt(StakeCredType t) {
        return t == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    private static int drepTypeInt(DrepType t) {
        return switch (t) {
            case ADDR_KEYHASH -> 0;
            case SCRIPTHASH -> 1;
            case ABSTAIN -> 2;
            case NO_CONFIDENCE -> 3;
        };
    }

    private static String epochDelegKey(int epoch, int credType, String credHash) {
        return epoch + ":" + credType + ":" + credHash;
    }

    private int epochForSlot(long slot) {
        long epochLength = epochParamProvider.getEpochLength();
        long shelleyStart = epochParamProvider.getShelleyStartSlot();
        if (shelleyStart <= 0) return (int) (slot / epochLength);
        long byronEpochLen = epochParamProvider.getByronSlotsPerEpoch();
        long shelleyStartEpoch = shelleyStart / byronEpochLen;
        return (int) (shelleyStartEpoch + (slot - shelleyStart) / epochLength);
    }

    // --- LedgerStateProvider reads ---

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        var entry = accounts.get(credKey(credType, credentialHash));
        return entry != null ? Optional.of(entry.reward) : Optional.empty();
    }

    @Override
    public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
        var entry = accounts.get(credKey(credType, credentialHash));
        return entry != null ? Optional.of(entry.deposit) : Optional.empty();
    }

    @Override
    public Optional<String> getDelegatedPool(int credType, String credentialHash) {
        var entry = poolDelegations.get(credKey(credType, credentialHash));
        return entry != null ? Optional.of(entry.poolHash) : Optional.empty();
    }

    @Override
    public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
        var entry = drepDelegations.get(credKey(credType, credentialHash));
        return entry != null ? Optional.of(new DRepDelegation(entry.drepType, entry.drepHash)) : Optional.empty();
    }

    @Override
    public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
        return accounts.containsKey(credKey(credType, credentialHash));
    }

    @Override
    public BigInteger getTotalDeposited() {
        return totalDeposited;
    }

    @Override
    public boolean isPoolRegistered(String poolHash) {
        return poolDeposits.containsKey(poolHash);
    }

    @Override
    public Optional<BigInteger> getPoolDeposit(String poolHash) {
        return Optional.ofNullable(poolDeposits.get(poolHash));
    }

    @Override
    public Optional<Long> getPoolRetirementEpoch(String poolHash) {
        return Optional.ofNullable(poolRetirements.get(poolHash));
    }

    // --- DRep State reads ---

    @Override
    public boolean isDRepRegistered(int credType, String credentialHash) {
        return drepRegistrations.containsKey(credKey(credType, credentialHash));
    }

    @Override
    public Optional<BigInteger> getDRepDeposit(int credType, String credentialHash) {
        return Optional.ofNullable(drepRegistrations.get(credKey(credType, credentialHash)));
    }

    // --- Committee State reads ---

    @Override
    public boolean isCommitteeMember(int credType, String coldCredentialHash) {
        return committeeHotKeys.containsKey(credKey(credType, coldCredentialHash));
    }

    @Override
    public Optional<String> getCommitteeHotCredential(int credType, String coldCredentialHash) {
        return Optional.ofNullable(committeeHotKeys.get(credKey(credType, coldCredentialHash)));
    }

    @Override
    public boolean hasCommitteeMemberResigned(int credType, String coldCredentialHash) {
        return committeeResignations.containsKey(credKey(credType, coldCredentialHash));
    }

    // --- MIR State reads ---

    @Override
    public Optional<BigInteger> getInstantReward(int credType, String credentialHash) {
        return Optional.ofNullable(mirRewards.get(credKey(credType, credentialHash)));
    }

    @Override
    public BigInteger getMirPotTransfer(boolean toReserves) {
        return toReserves ? mirPotToReserves : mirPotToTreasury;
    }

    // --- Epoch Delegation Snapshot queries ---

    @Override
    public Optional<String> getEpochDelegation(int epoch, int credType, String credentialHash) {
        return Optional.ofNullable(epochDelegSnapshots.get(epochDelegKey(epoch, credType, credentialHash)));
    }

    @Override
    public List<LedgerStateProvider.EpochDelegator> getPoolDelegatorsAtEpoch(int epoch, String poolHash) {
        List<LedgerStateProvider.EpochDelegator> result = new ArrayList<>();
        String prefix = epoch + ":";
        for (var entry : epochDelegSnapshots.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            if (!entry.getValue().equals(poolHash)) continue;
            // Parse "epoch:credType:credHash"
            String[] parts = entry.getKey().split(":");
            if (parts.length == 3) {
                result.add(new LedgerStateProvider.EpochDelegator(Integer.parseInt(parts[1]), parts[2]));
            }
        }
        return result;
    }

    @Override
    public int getLatestSnapshotEpoch() {
        return lastSnapshotEpoch;
    }

    private static int credTypeFromModel(com.bloxbean.cardano.yaci.core.model.Credential cred) {
        return cred.getType() == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    // --- Block application ---

    @Override
    public void applyBlock(BlockAppliedEvent event) {
        if (!enabled || event.block() == null) return;

        Block block = event.block();
        long slot = event.slot();
        BlockDelta delta = new BlockDelta(event.blockNumber(), slot);

        // Epoch boundary detection — snapshot delegation state from end of previous epoch
        int currentEpoch = epochForSlot(slot);
        if (lastSnapshotEpoch == -1) {
            lastSnapshotEpoch = currentEpoch;
        } else if (currentEpoch > lastSnapshotEpoch) {
            // Snapshot labeled as previous epoch (end-of-epoch state), matching yaci-store convention
            createDelegationSnapshot(currentEpoch - 1);
            pruneOldSnapshots(currentEpoch - 1 - SNAPSHOT_RETENTION_EPOCHS);
        }

        List<Integer> invList = block.getInvalidTransactions();
        Set<Integer> invalidIdx = (invList != null) ? new HashSet<>(invList) : Collections.emptySet();
        List<TransactionBody> txs = block.getTransactionBodies();

        if (txs != null) {
            for (int txIdx = 0; txIdx < txs.size(); txIdx++) {
                if (invalidIdx.contains(txIdx)) continue;
                TransactionBody tx = txs.get(txIdx);

                List<Certificate> certs = tx.getCertificates();
                if (certs != null) {
                    for (int certIdx = 0; certIdx < certs.size(); certIdx++) {
                        processCertificate(certs.get(certIdx), slot, txIdx, certIdx, delta);
                    }
                }

                Map<String, BigInteger> withdrawals = tx.getWithdrawals();
                if (withdrawals != null) {
                    for (var entry : withdrawals.entrySet()) {
                        processWithdrawal(entry.getKey(), entry.getValue(), delta);
                    }
                }
            }
        }

        deltaStack.push(delta);
    }

    private void processCertificate(Certificate cert, long slot, int txIdx, int certIdx, BlockDelta delta) {
        switch (cert) {
            case StakeRegistration sr ->
                    registerStake(sr.getStakeCredential(),
                            epochParamProvider.getKeyDeposit(0), delta);
            case RegCert rc ->
                    registerStake(rc.getStakeCredential(),
                            rc.getCoin() != null ? rc.getCoin() : BigInteger.ZERO, delta);
            case StakeDeregistration sd ->
                    deregisterStake(sd.getStakeCredential(), delta);
            case UnregCert uc ->
                    deregisterStake(uc.getStakeCredential(), delta);
            case StakeDelegation sd ->
                    delegateToPool(sd.getStakeCredential(), sd.getStakePoolId().getPoolKeyHash(),
                            slot, txIdx, certIdx, delta);
            case VoteDelegCert vd ->
                    delegateToDRep(vd.getStakeCredential(), vd.getDrep(),
                            slot, txIdx, certIdx, delta);
            case StakeVoteDelegCert svd -> {
                delegateToPool(svd.getStakeCredential(), svd.getPoolKeyHash(),
                        slot, txIdx, certIdx, delta);
                delegateToDRep(svd.getStakeCredential(), svd.getDrep(),
                        slot, txIdx, certIdx, delta);
            }
            case StakeRegDelegCert srd -> {
                registerStake(srd.getStakeCredential(),
                        srd.getCoin() != null ? srd.getCoin() : BigInteger.ZERO, delta);
                delegateToPool(srd.getStakeCredential(), srd.getPoolKeyHash(),
                        slot, txIdx, certIdx, delta);
            }
            case VoteRegDelegCert vrd -> {
                registerStake(vrd.getStakeCredential(),
                        vrd.getCoin() != null ? vrd.getCoin() : BigInteger.ZERO, delta);
                delegateToDRep(vrd.getStakeCredential(), vrd.getDrep(),
                        slot, txIdx, certIdx, delta);
            }
            case StakeVoteRegDelegCert svrd -> {
                registerStake(svrd.getStakeCredential(),
                        svrd.getCoin() != null ? svrd.getCoin() : BigInteger.ZERO, delta);
                delegateToPool(svrd.getStakeCredential(), svrd.getPoolKeyHash(),
                        slot, txIdx, certIdx, delta);
                delegateToDRep(svrd.getStakeCredential(), svrd.getDrep(),
                        slot, txIdx, certIdx, delta);
            }
            case PoolRegistration pr -> {
                String poolHash = pr.getPoolParams().getOperator();
                delta.prevPoolDeposits.put(poolHash, poolDeposits.get(poolHash));
                poolDeposits.put(poolHash, epochParamProvider.getPoolDeposit(0));
                if (poolRetirements.containsKey(poolHash)) {
                    delta.prevPoolRetirements.put(poolHash, poolRetirements.get(poolHash));
                    poolRetirements.remove(poolHash);
                }
            }
            case PoolRetirement pr -> {
                delta.prevPoolRetirements.put(pr.getPoolKeyHash(), poolRetirements.get(pr.getPoolKeyHash()));
                poolRetirements.put(pr.getPoolKeyHash(), pr.getEpoch());
            }
            case RegDrepCert rd -> {
                int ct = credTypeFromModel(rd.getDrepCredential());
                String key = credKey(ct, rd.getDrepCredential().getHash());
                BigInteger deposit = rd.getCoin() != null ? rd.getCoin() : BigInteger.ZERO;
                delta.prevDRepRegistrations.put(key, drepRegistrations.get(key));
                drepRegistrations.put(key, deposit);
                if (delta.prevTotalDeposited == null) delta.prevTotalDeposited = totalDeposited;
                totalDeposited = totalDeposited.add(deposit);
            }
            case UnregDrepCert ud -> {
                int ct = credTypeFromModel(ud.getDrepCredential());
                String key = credKey(ct, ud.getDrepCredential().getHash());
                BigInteger prevDeposit = drepRegistrations.get(key);
                delta.prevDRepRegistrations.put(key, prevDeposit);
                drepRegistrations.remove(key);
                if (prevDeposit != null) {
                    if (delta.prevTotalDeposited == null) delta.prevTotalDeposited = totalDeposited;
                    totalDeposited = totalDeposited.subtract(prevDeposit);
                    if (totalDeposited.signum() < 0) totalDeposited = BigInteger.ZERO;
                }
            }
            case UpdateDrepCert upd -> {
                // Update only changes anchor — deposit preserved. No-op for state tracking.
            }
            case AuthCommitteeHotCert ac -> {
                int coldCt = credTypeFromModel(ac.getCommitteeColdCredential());
                String coldKey = credKey(coldCt, ac.getCommitteeColdCredential().getHash());
                String hotHash = ac.getCommitteeHotCredential().getHash();
                delta.prevCommitteeHotKeys.put(coldKey, committeeHotKeys.get(coldKey));
                committeeHotKeys.put(coldKey, hotHash);
            }
            case ResignCommitteeColdCert rc -> {
                int ct = credTypeFromModel(rc.getCommitteeColdCredential());
                String coldKey = credKey(ct, rc.getCommitteeColdCredential().getHash());
                delta.prevCommitteeResignations.put(coldKey, committeeResignations.get(coldKey));
                committeeResignations.put(coldKey, Boolean.TRUE);
            }
            case MoveInstataneous mir -> {
                processMir(mir, delta);
            }
            default -> { /* Unknown certificate type */ }
        }
    }

    private void registerStake(StakeCredential cred, BigInteger deposit, BlockDelta delta) {
        String key = credKey(credTypeInt(cred.getType()), cred.getHash());
        delta.prevAccounts.put(key, accounts.get(key));
        accounts.put(key, new StakeAccountEntry(BigInteger.ZERO, deposit));
        if (delta.prevTotalDeposited == null) {
            delta.prevTotalDeposited = totalDeposited;
        }
        totalDeposited = totalDeposited.add(deposit);
    }

    private void deregisterStake(StakeCredential cred, BlockDelta delta) {
        String key = credKey(credTypeInt(cred.getType()), cred.getHash());
        var prev = accounts.get(key);
        delta.prevAccounts.put(key, prev);
        if (prev != null) {
            if (delta.prevTotalDeposited == null) {
                delta.prevTotalDeposited = totalDeposited;
            }
            totalDeposited = totalDeposited.subtract(prev.deposit);
            if (totalDeposited.signum() < 0) totalDeposited = BigInteger.ZERO;
        }
        accounts.remove(key);
        // Per Haskell ledger: deregistration completely removes the entry from dsAccounts,
        // discarding the pool and DRep delegations. Re-registration starts fresh with no delegation.
        delta.prevPoolDelegations.put(key, poolDelegations.get(key));
        poolDelegations.remove(key);
        delta.prevDRepDelegations.put(key, drepDelegations.get(key));
        drepDelegations.remove(key);
    }

    private void delegateToPool(StakeCredential cred, String poolHash,
                                long slot, int txIdx, int certIdx, BlockDelta delta) {
        String key = credKey(credTypeInt(cred.getType()), cred.getHash());
        delta.prevPoolDelegations.put(key, poolDelegations.get(key));
        poolDelegations.put(key, new PoolDelegEntry(poolHash, slot, txIdx, certIdx));
    }

    private void delegateToDRep(StakeCredential cred, Drep drep,
                                long slot, int txIdx, int certIdx, BlockDelta delta) {
        String key = credKey(credTypeInt(cred.getType()), cred.getHash());
        delta.prevDRepDelegations.put(key, drepDelegations.get(key));
        drepDelegations.put(key, new DRepDelegEntry(
                drepTypeInt(drep.getType()), drep.getHash(), slot, txIdx, certIdx));
    }

    private void processMir(MoveInstataneous mir, BlockDelta delta) {
        Map<StakeCredential, BigInteger> credMap = mir.getStakeCredentialCoinMap();

        if (credMap != null && !credMap.isEmpty()) {
            for (var entry : credMap.entrySet()) {
                StakeCredential cred = entry.getKey();
                BigInteger amount = entry.getValue();
                if (amount == null || amount.signum() <= 0) continue;

                String key = credKey(credTypeInt(cred.getType()), cred.getHash());
                delta.prevMirRewards.put(key, mirRewards.get(key));
                BigInteger existing = mirRewards.getOrDefault(key, BigInteger.ZERO);
                mirRewards.put(key, existing.add(amount));
            }
        } else if (mir.getAccountingPotCoin() != null && mir.getAccountingPotCoin().signum() > 0) {
            if (mir.isTreasury()) {
                // treasury → reserves
                delta.prevMirPotToReserves = mirPotToReserves;
                mirPotToReserves = mirPotToReserves.add(mir.getAccountingPotCoin());
            } else {
                // reserves → treasury
                delta.prevMirPotToTreasury = mirPotToTreasury;
                mirPotToTreasury = mirPotToTreasury.add(mir.getAccountingPotCoin());
            }
        }
    }

    private void processWithdrawal(String rewardAddrHex, BigInteger amount, BlockDelta delta) {
        byte[] addrBytes = HexUtil.decodeHexString(rewardAddrHex);
        if (addrBytes.length < 29) return;

        int headerByte = addrBytes[0] & 0xFF;
        int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
        byte[] credHash = new byte[28];
        System.arraycopy(addrBytes, 1, credHash, 0, 28);
        String key = credKey(credType, HexUtil.encodeHexString(credHash));

        var prev = accounts.get(key);
        if (prev == null) return;

        delta.prevAccounts.put(key, prev);
        BigInteger newReward = prev.reward.subtract(amount);
        if (newReward.signum() < 0) newReward = BigInteger.ZERO;
        accounts.put(key, new StakeAccountEntry(newReward, prev.deposit));
    }

    private void createDelegationSnapshot(int epoch) {
        for (var entry : poolDelegations.entrySet()) {
            // entry key is "credType:credHash", value is PoolDelegEntry
            String[] parts = entry.getKey().split(":");
            if (parts.length == 2) {
                int credType = Integer.parseInt(parts[0]);
                String credHash = parts[1];
                epochDelegSnapshots.put(epochDelegKey(epoch, credType, credHash), entry.getValue().poolHash());
            }
        }
        lastSnapshotEpoch = epoch;
    }

    private void pruneOldSnapshots(int oldestToKeep) {
        if (oldestToKeep <= 0) return;
        epochDelegSnapshots.keySet().removeIf(key -> {
            int epoch = Integer.parseInt(key.substring(0, key.indexOf(':')));
            return epoch < oldestToKeep;
        });
    }

    // --- Rollback ---

    @Override
    public void rollbackTo(RollbackEvent event) {
        if (!enabled) return;
        long targetSlot = event.target().getSlot();

        while (!deltaStack.isEmpty()) {
            BlockDelta delta = deltaStack.peek();
            if (delta.slot <= targetSlot) break;

            deltaStack.pop();

            // Restore accounts
            for (var e : delta.prevAccounts.entrySet()) {
                if (e.getValue() != null) accounts.put(e.getKey(), e.getValue());
                else accounts.remove(e.getKey());
            }
            // Restore pool delegations
            for (var e : delta.prevPoolDelegations.entrySet()) {
                if (e.getValue() != null) poolDelegations.put(e.getKey(), e.getValue());
                else poolDelegations.remove(e.getKey());
            }
            // Restore drep delegations
            for (var e : delta.prevDRepDelegations.entrySet()) {
                if (e.getValue() != null) drepDelegations.put(e.getKey(), e.getValue());
                else drepDelegations.remove(e.getKey());
            }
            // Restore pool deposits
            for (var e : delta.prevPoolDeposits.entrySet()) {
                if (e.getValue() != null) poolDeposits.put(e.getKey(), e.getValue());
                else poolDeposits.remove(e.getKey());
            }
            // Restore pool retirements
            for (var e : delta.prevPoolRetirements.entrySet()) {
                if (e.getValue() != null) poolRetirements.put(e.getKey(), e.getValue());
                else poolRetirements.remove(e.getKey());
            }
            // Restore MIR rewards
            for (var e : delta.prevMirRewards.entrySet()) {
                if (e.getValue() != null) mirRewards.put(e.getKey(), e.getValue());
                else mirRewards.remove(e.getKey());
            }
            if (delta.prevMirPotToReserves != null) mirPotToReserves = delta.prevMirPotToReserves;
            if (delta.prevMirPotToTreasury != null) mirPotToTreasury = delta.prevMirPotToTreasury;
            // Restore drep registrations
            for (var e : delta.prevDRepRegistrations.entrySet()) {
                if (e.getValue() != null) drepRegistrations.put(e.getKey(), e.getValue());
                else drepRegistrations.remove(e.getKey());
            }
            // Restore committee hot keys
            for (var e : delta.prevCommitteeHotKeys.entrySet()) {
                if (e.getValue() != null) committeeHotKeys.put(e.getKey(), e.getValue());
                else committeeHotKeys.remove(e.getKey());
            }
            // Restore committee resignations
            for (var e : delta.prevCommitteeResignations.entrySet()) {
                if (e.getValue() != null) committeeResignations.put(e.getKey(), e.getValue());
                else committeeResignations.remove(e.getKey());
            }
            // Restore total deposited
            if (delta.prevTotalDeposited != null) {
                totalDeposited = delta.prevTotalDeposited;
            }
        }

        // Clean up epoch snapshots beyond target
        int targetEpoch = epochForSlot(targetSlot);
        if (targetEpoch < lastSnapshotEpoch) {
            epochDelegSnapshots.keySet().removeIf(key -> {
                int epoch = Integer.parseInt(key.substring(0, key.indexOf(':')));
                return epoch > targetEpoch;
            });
            lastSnapshotEpoch = targetEpoch;
        }
    }

    @Override
    public void reconcile(ChainState chainState) {
        // In-memory store doesn't persist — no-op
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // --- Listing ---

    @Override
    public List<StakeRegistrationEntry> listStakeRegistrations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        return accounts.entrySet().stream()
                .skip(skip).limit(count)
                .map(e -> {
                    String[] parts = e.getKey().split(":");
                    return new StakeRegistrationEntry(
                            Integer.parseInt(parts[0]), parts[1],
                            e.getValue().reward(), e.getValue().deposit());
                })
                .toList();
    }

    @Override
    public List<PoolDelegationEntry> listPoolDelegations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        return poolDelegations.entrySet().stream()
                .skip(skip).limit(count)
                .map(e -> {
                    String[] parts = e.getKey().split(":");
                    var v = e.getValue();
                    return new PoolDelegationEntry(
                            Integer.parseInt(parts[0]), parts[1],
                            v.poolHash(), v.slot(), v.txIdx(), v.certIdx());
                })
                .toList();
    }

    @Override
    public List<DRepDelegationEntry> listDRepDelegations(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        return drepDelegations.entrySet().stream()
                .skip(skip).limit(count)
                .map(e -> {
                    String[] parts = e.getKey().split(":");
                    var v = e.getValue();
                    return new DRepDelegationEntry(
                            Integer.parseInt(parts[0]), parts[1],
                            v.drepType(), v.drepHash(), v.slot(), v.txIdx(), v.certIdx());
                })
                .toList();
    }

    @Override
    public List<PoolEntry> listPools(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        return poolDeposits.entrySet().stream()
                .skip(skip).limit(count)
                .map(e -> new PoolEntry(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public List<PoolRetirementEntry> listPoolRetirements(int page, int count) {
        if (page < 1) page = 1;
        if (count < 1) count = 1;
        int skip = (page - 1) * count;
        return poolRetirements.entrySet().stream()
                .skip(skip).limit(count)
                .map(e -> new PoolRetirementEntry(e.getKey(), e.getValue()))
                .toList();
    }

    // --- Delta tracking ---

    private static class BlockDelta {
        final long blockNumber;
        final long slot;
        final Map<String, StakeAccountEntry> prevAccounts = new HashMap<>();
        final Map<String, PoolDelegEntry> prevPoolDelegations = new HashMap<>();
        final Map<String, DRepDelegEntry> prevDRepDelegations = new HashMap<>();
        final Map<String, BigInteger> prevPoolDeposits = new HashMap<>();
        final Map<String, Long> prevPoolRetirements = new HashMap<>();
        final Map<String, BigInteger> prevDRepRegistrations = new HashMap<>();
        final Map<String, String> prevCommitteeHotKeys = new HashMap<>();
        final Map<String, Boolean> prevCommitteeResignations = new HashMap<>();
        final Map<String, BigInteger> prevMirRewards = new HashMap<>();
        BigInteger prevMirPotToReserves;
        BigInteger prevMirPotToTreasury;
        BigInteger prevTotalDeposited;

        BlockDelta(long blockNumber, long slot) {
            this.blockNumber = blockNumber;
            this.slot = slot;
        }
    }
}
