package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.ledgerstate.AccountStateCborCodec;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import org.rocksdb.RocksDB;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksIterator;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Computes DRep stake distribution at epoch boundaries.
 * <p>
 * For each stake credential delegated to a DRep:
 * <pre>
 * drep_voting_stake[drep] += delegated_utxo_stake[cred]
 *                          + unwithdraw_rewards[cred]
 *                          + proposal_deposits_by_credential[cred]
 * </pre>
 * <p>
 * Rules:
 * <ul>
 *   <li>Only delegations to REGISTERED (active) DReps are included</li>
 *   <li>ALWAYS_ABSTAIN and ALWAYS_NO_CONFIDENCE virtual DReps always count</li>
 *   <li>Proposal deposits contribute to the proposer's staking credential's DRep</li>
 *   <li>Stake credential must be registered (has active account entry)</li>
 * </ul>
 */
public class DRepDistributionCalculator {
    private static final Logger log = LoggerFactory.getLogger(DRepDistributionCalculator.class);

    // DRep type constants (match DrepType enum ordinals and CBOR encoding)
    static final int DREP_KEY = 0;      // ADDR_KEYHASH
    static final int DREP_SCRIPT = 1;   // SCRIPTHASH
    private static final int DREP_ABSTAIN = 2;  // ABSTAIN
    private static final int DREP_NO_CONF = 3;  // NO_CONFIDENCE

    // Special synthetic DRep hashes for virtual DReps in the distribution map
    static final String ABSTAIN_HASH = "abstain";
    static final String NO_CONFIDENCE_HASH = "no_confidence";

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final ColumnFamilyHandle cfEpochSnapshot;
    private final GovernanceStateStore governanceStore;

    public DRepDistributionCalculator(RocksDB db, ColumnFamilyHandle cfState,
                                      ColumnFamilyHandle cfEpochSnapshot,
                                      GovernanceStateStore governanceStore) {
        this.db = db;
        this.cfState = cfState;
        this.cfEpochSnapshot = cfEpochSnapshot;
        this.governanceStore = governanceStore;
    }

    /**
     * Calculate the DRep stake distribution for the given epoch.
     * <p>
     * This iterates all DRep delegations (PREFIX_DREP_DELEG = 0x03) and for each:
     * <ol>
     *   <li>Checks if the delegated-to DRep is registered and active (or virtual)</li>
     *   <li>Looks up the credential's stake (UTXO balance + rewards)</li>
     *   <li>Adds proposal deposits for the credential</li>
     *   <li>Accumulates into the DRep's total voting power</li>
     * </ol>
     *
     * @param snapshotEpoch     The epoch whose delegation snapshot to use for stake amounts.
     *                          This is the PREVIOUS epoch (epoch N-1), whose snapshot was taken
     *                          at the boundary between epoch N-1 and N.
     * @param utxoBalances      Pre-aggregated UTXO balances per credential from the snapshot step.
     *                          Used for DRep stake calculation (not pool-delegation-only).
     *                          Pass null to fall back to epoch snapshot (pool-delegated only).
     * @return Map of DRep identifier to total delegated stake
     */
    public Map<DRepDistKey, BigInteger> calculate(int snapshotEpoch,
                                                   Map<com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey,
                                                           BigInteger> utxoBalances,
                                                   Map<String, BigInteger> spendableRewardRest) throws RocksDBException {
        Map<DRepDistKey, BigInteger> distribution = new HashMap<>();

        // Pre-compute DRep states for delegation validation.
        // Per Amaru (governance.rs lines 94-117): include DRep if registeredAt > previousDeregistration.
        // This excludes deregistered DReps (previousDeregistration > registeredAt) but includes:
        //   - Never-deregistered DReps (previousDeregistration = null)
        //   - Re-registered DReps (registeredAt > previousDeregistration)
        //   - Expired DReps (expiry is separate from deregistration)
        Map<CredentialKey, DRepStateRecord> allDRepStates = governanceStore.getAllDRepStates();
        Map<CredentialKey, DRepStateRecord> activeDReps = new HashMap<>();
        for (var entry : allDRepStates.entrySet()) {
            DRepStateRecord rec = entry.getValue();
            Long prevDeregSlot = rec.previousDeregistrationSlot();
            // Include if never deregistered OR registered after last deregistration
            if (prevDeregSlot == null || rec.registeredAtSlot() > prevDeregSlot) {
                activeDReps.put(entry.getKey(), rec);
            }
        }

        // Pre-compute: proposal deposits by return address credential
        // Only includes deposits for credentials that have a DRep delegation
        Map<String, BigInteger> proposalDepositsByCredential = computeProposalDepositsByCredential();

        // Iterate all DRep delegations
        byte[] seekKey = new byte[]{DefaultAccountStateStore.PREFIX_DREP_DELEG};
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != DefaultAccountStateStore.PREFIX_DREP_DELEG) break;

                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, key.length));

                // Decode delegation target
                var deleg = AccountStateCborCodec.decodeDRepDelegation(it.value());
                int drepType = deleg.drepType();
                String drepHash = deleg.drepHash();

                // Check if delegated-to DRep is valid (must be registered, delegation after deregistration)
                DRepDistKey drepKey = resolveDRepKey(drepType, drepHash, activeDReps, deleg.slot());
                if (drepKey == null) {
                    it.next();
                    continue;
                }

                // Check if the stake credential is registered
                byte[] acctKey = accountKey(credType, credHash);
                byte[] acctVal = db.get(cfState, acctKey);
                if (acctVal == null) {
                    it.next();
                    continue;
                }

                // Get stake amount: UTXO balance + unwithdraw rewards.
                // Use actual UTXO balances (all credentials) rather than pool delegation snapshot
                // (which only has pool-delegated credentials). This matches Haskell/DBSync behavior.
                BigInteger stake = BigInteger.ZERO;
                if (utxoBalances != null) {
                    var credentialKey = new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(credType, credHash);
                    stake = utxoBalances.getOrDefault(credentialKey, BigInteger.ZERO);
                } else {
                    stake = getSnapshotStake(snapshotEpoch, credType, credHash);
                }
                BigInteger rewards = AccountStateCborCodec.decodeStakeAccount(acctVal).reward();
                // Add spendable reward_rest (proposal refunds, treasury withdrawals)
                String restKey = credType + ":" + credHash;
                BigInteger rewardRest = (spendableRewardRest != null)
                        ? spendableRewardRest.getOrDefault(restKey, BigInteger.ZERO) : BigInteger.ZERO;
                BigInteger total = stake.add(rewards).add(rewardRest);

                // Add proposal deposits for this credential (only if they have DRep delegation,
                // which they do since we're iterating DRep delegations)
                String credKey = credType + ":" + credHash;
                BigInteger proposalDeposits = proposalDepositsByCredential.getOrDefault(credKey, BigInteger.ZERO);
                total = total.add(proposalDeposits);

                // Include zero-amount DReps to match DBSync drep_distr
                distribution.merge(drepKey, total, BigInteger::add);

                it.next();
            }
        }

        log.info("Computed DRep distribution for snapshot epoch {}: {} DReps, {} total delegations",
                snapshotEpoch, distribution.size(),
                distribution.values().stream().reduce(BigInteger.ZERO, BigInteger::add));

        return distribution;
    }

    /**
     * Resolve a DRep delegation target to a distribution key.
     * Returns null if the DRep is not registered, or if the delegation predates
     * the DRep's previous deregistration (per Amaru stake_distribution.rs).
     */
    private DRepDistKey resolveDRepKey(int drepType, String drepHash,
                                       Map<CredentialKey, DRepStateRecord> activeDReps,
                                       long delegSlot) {
        return switch (drepType) {
            case DREP_KEY, DREP_SCRIPT -> {
                // Regular DRep — must be registered
                CredentialKey ck = new CredentialKey(drepType, drepHash);
                DRepStateRecord drepState = activeDReps.get(ck);
                if (drepState == null) {
                    yield null;
                }

                // Per Amaru (stake_distribution.rs): delegation valid only if made AFTER
                // the DRep's previous deregistration. This is a per-delegator check.
                Long prevDeregSlot = drepState.previousDeregistrationSlot();
                if (prevDeregSlot != null && delegSlot <= prevDeregSlot) {
                    yield null;
                }

                yield new DRepDistKey(drepType, drepHash);
            }
            case DREP_ABSTAIN -> new DRepDistKey(DREP_ABSTAIN, ABSTAIN_HASH);
            case DREP_NO_CONF -> new DRepDistKey(DREP_NO_CONF, NO_CONFIDENCE_HASH);
            default -> null;
        };
    }

    /**
     * Compute proposal deposits grouped by the proposer's staking credential.
     * The return address of each active proposal is a reward account; we extract
     * the staking credential and sum deposits per credential.
     */
    private Map<String, BigInteger> computeProposalDepositsByCredential() throws RocksDBException {
        Map<String, BigInteger> result = new HashMap<>();
        Map<GovActionId, GovActionRecord> proposals = governanceStore.getAllActiveProposals();

        for (var entry : proposals.entrySet()) {
            GovActionRecord record = entry.getValue();
            String returnAddr = record.returnAddress();
            if (returnAddr == null || returnAddr.isEmpty()) continue;

            // Extract credential from reward account hex
            // Reward account format: 1-byte header + 28-byte credential hash
            // Header: e0/e1 for mainnet key/script, f0/f1 for testnet key/script
            String credKey = extractCredentialFromRewardAccount(returnAddr);
            if (credKey != null) {
                result.merge(credKey, record.deposit(), BigInteger::add);
            }
        }
        return result;
    }

    /**
     * Extract "credType:credHash" from a reward account hex string.
     * Reward account: header(1 byte) + credential_hash(28 bytes).
     * Header bit 4: 0=key, 1=script. Bits 0-3: network tag.
     */
    static String extractCredentialFromRewardAccount(String rewardAccountHex) {
        if (rewardAccountHex == null || rewardAccountHex.length() < 58) return null; // 1+28 = 29 bytes = 58 hex
        try {
            int headerByte = Integer.parseInt(rewardAccountHex.substring(0, 2), 16);
            int credType = ((headerByte & 0x10) != 0) ? 1 : 0; // bit 4: 0=key, 1=script
            String credHash = rewardAccountHex.substring(2, 58);
            return credType + ":" + credHash;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get the UTXO stake for a credential from the epoch delegation snapshot.
     * The snapshot (cfEpochSnapshot) stores {0: poolHash, 1: amount} per credential per epoch.
     * Key format: epoch(4 BE) + credType(1) + credHash(28).
     * The amount field is the aggregated UTXO balance at epoch boundary.
     */
    private BigInteger getSnapshotStake(int epoch, int credType, String credHash) {
        if (cfEpochSnapshot == null) return BigInteger.ZERO;
        try {
            byte[] hashBytes = HexUtil.decodeHexString(credHash);
            byte[] snapshotKey = new byte[4 + 1 + hashBytes.length];
            java.nio.ByteBuffer.wrap(snapshotKey, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
            snapshotKey[4] = (byte) credType;
            System.arraycopy(hashBytes, 0, snapshotKey, 5, hashBytes.length);

            byte[] val = db.get(cfEpochSnapshot, snapshotKey);
            if (val == null) return BigInteger.ZERO;

            var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(val);
            return snapshot.amount();
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    /** Build account key matching PREFIX_ACCT pattern in DefaultAccountStateStore */
    private static byte[] accountKey(int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hash.length];
        key[0] = DefaultAccountStateStore.PREFIX_ACCT;
        key[1] = (byte) credType;
        System.arraycopy(hash, 0, key, 2, hash.length);
        return key;
    }

    /**
     * Key for DRep distribution map entries.
     * For regular DReps: drepType + drepHash.
     * For virtual DReps: DREP_ABSTAIN/"abstain" or DREP_NO_CONF/"no_confidence".
     */
    public record DRepDistKey(int drepType, String drepHash) {}
}
