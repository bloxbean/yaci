package com.bloxbean.cardano.yaci.node.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * RocksDB-backed governance state store.
 * Tracks proposals, votes, DRep state, committee members, constitution, and dormant epochs.
 * <p>
 * All writes go through WriteBatch + DeltaOp for rollback support,
 * following the same pattern as DefaultAccountStateStore.
 */
public class GovernanceStateStore {
    private static final Logger log = LoggerFactory.getLogger(GovernanceStateStore.class);

    // Key prefixes for governance state in cfState column family
    static final byte PREFIX_GOV_ACTION = 0x60;
    static final byte PREFIX_VOTE = 0x61;
    static final byte PREFIX_DREP_STATE = 0x62;
    static final byte PREFIX_COMMITTEE_MEMBER = 0x63;
    static final byte PREFIX_CONSTITUTION = 0x64;
    static final byte PREFIX_DORMANT_EPOCHS = 0x65;
    static final byte PREFIX_DREP_DIST = 0x66;
    static final byte PREFIX_EPOCH_PROPOSALS_FLAG = 0x67;
    static final byte PREFIX_EPOCH_DONATIONS = 0x68;
    static final byte PREFIX_LAST_ENACTED = 0x69;
    static final byte PREFIX_RATIFIED_IN_EPOCH = 0x6A;
    static final byte PREFIX_COMMITTEE_THRESHOLD = 0x6B;

    // Delta op types — same values as DefaultAccountStateStore
    private static final byte OP_PUT = 0x01;
    private static final byte OP_DELETE = 0x02;

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;

    public GovernanceStateStore(RocksDB db, ColumnFamilyHandle cfState) {
        this.db = db;
        this.cfState = cfState;
    }

    // ===== Key builders =====

    /** Key: PREFIX_GOV_ACTION(1) + txHash(32) + govIdx(2 BE) */
    static byte[] govActionKey(String txHash, int govActionIndex) {
        byte[] hashBytes = HexUtil.decodeHexString(txHash);
        byte[] key = new byte[1 + 32 + 2];
        key[0] = PREFIX_GOV_ACTION;
        System.arraycopy(hashBytes, 0, key, 1, 32);
        key[33] = (byte) (govActionIndex >> 8);
        key[34] = (byte) govActionIndex;
        return key;
    }

    static byte[] govActionKey(GovActionId id) {
        return govActionKey(id.getTransactionId(), id.getGov_action_index());
    }

    /** Key: PREFIX_VOTE(1) + txHash(32) + govIdx(2 BE) + voterType(1) + voterHash(28) */
    static byte[] voteKey(String proposalTxHash, int proposalIdx, int voterType, String voterHash) {
        byte[] pHash = HexUtil.decodeHexString(proposalTxHash);
        byte[] vHash = HexUtil.decodeHexString(voterHash);
        byte[] key = new byte[1 + 32 + 2 + 1 + 28];
        key[0] = PREFIX_VOTE;
        System.arraycopy(pHash, 0, key, 1, 32);
        key[33] = (byte) (proposalIdx >> 8);
        key[34] = (byte) proposalIdx;
        key[35] = (byte) voterType;
        System.arraycopy(vHash, 0, key, 36, Math.min(vHash.length, 28));
        return key;
    }

    /** Prefix for scanning all votes for a specific proposal */
    static byte[] voteKeyPrefix(String proposalTxHash, int proposalIdx) {
        byte[] pHash = HexUtil.decodeHexString(proposalTxHash);
        byte[] prefix = new byte[1 + 32 + 2];
        prefix[0] = PREFIX_VOTE;
        System.arraycopy(pHash, 0, prefix, 1, 32);
        prefix[33] = (byte) (proposalIdx >> 8);
        prefix[34] = (byte) proposalIdx;
        return prefix;
    }

    /** Key: PREFIX_DREP_STATE(1) + credType(1) + credHash(28) */
    static byte[] drepStateKey(int credType, String credHash) {
        byte[] hashBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + 28];
        key[0] = PREFIX_DREP_STATE;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, Math.min(hashBytes.length, 28));
        return key;
    }

    /** Key: PREFIX_COMMITTEE_MEMBER(1) + credType(1) + coldHash(28) */
    static byte[] committeeMemberKey(int credType, String coldHash) {
        byte[] hashBytes = HexUtil.decodeHexString(coldHash);
        byte[] key = new byte[1 + 1 + 28];
        key[0] = PREFIX_COMMITTEE_MEMBER;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, Math.min(hashBytes.length, 28));
        return key;
    }

    /** Singleton key for constitution */
    static byte[] constitutionKey() {
        return new byte[]{PREFIX_CONSTITUTION};
    }

    /** Singleton key for dormant epochs set */
    static byte[] dormantEpochsKey() {
        return new byte[]{PREFIX_DORMANT_EPOCHS};
    }

    /** Key: PREFIX_DREP_DIST(1) + epoch(4 BE) + credType(1) + drepHash(28) */
    static byte[] drepDistKey(int epoch, int credType, String drepHash) {
        byte[] hashBytes = HexUtil.decodeHexString(drepHash);
        byte[] key = new byte[1 + 4 + 1 + 28];
        key[0] = PREFIX_DREP_DIST;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        key[5] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 6, Math.min(hashBytes.length, 28));
        return key;
    }

    /** Prefix for scanning all DRep dist entries for an epoch */
    static byte[] drepDistEpochPrefix(int epoch) {
        byte[] prefix = new byte[1 + 4];
        prefix[0] = PREFIX_DREP_DIST;
        ByteBuffer.wrap(prefix, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return prefix;
    }

    /** Key: PREFIX_EPOCH_PROPOSALS_FLAG(1) + epoch(4 BE) */
    static byte[] epochProposalsFlagKey(int epoch) {
        byte[] key = new byte[1 + 4];
        key[0] = PREFIX_EPOCH_PROPOSALS_FLAG;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    /** Key: PREFIX_EPOCH_DONATIONS(1) + epoch(4 BE) */
    static byte[] epochDonationsKey(int epoch) {
        byte[] key = new byte[1 + 4];
        key[0] = PREFIX_EPOCH_DONATIONS;
        ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);
        return key;
    }

    /** Key: PREFIX_LAST_ENACTED(1) + actionType(1) */
    static byte[] lastEnactedKey(GovActionType type) {
        return new byte[]{PREFIX_LAST_ENACTED, (byte) type.ordinal()};
    }

    /** Singleton key for committee quorum threshold */
    static byte[] committeeThresholdKey() {
        return new byte[]{PREFIX_COMMITTEE_THRESHOLD};
    }

    // ===== Proposal operations =====

    public void storeProposal(GovActionId id, GovActionRecord record,
                              WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = govActionKey(id);
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeGovAction(record);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Optional<GovActionRecord> getProposal(GovActionId id) throws RocksDBException {
        byte[] val = db.get(cfState, govActionKey(id));
        return val != null ? Optional.of(GovernanceCborCodec.decodeGovAction(val)) : Optional.empty();
    }

    public Optional<GovActionRecord> getProposal(String txHash, int govIdx) throws RocksDBException {
        byte[] val = db.get(cfState, govActionKey(txHash, govIdx));
        return val != null ? Optional.of(GovernanceCborCodec.decodeGovAction(val)) : Optional.empty();
    }

    /** Iterate all active proposals. Returns map of GovActionId -> GovActionRecord. */
    public Map<GovActionId, GovActionRecord> getAllActiveProposals() throws RocksDBException {
        Map<GovActionId, GovActionRecord> result = new LinkedHashMap<>();
        byte[] seekKey = new byte[]{PREFIX_GOV_ACTION};

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 35 || key[0] != PREFIX_GOV_ACTION) break;

                String txHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 1, 33));
                int govIdx = ((key[33] & 0xFF) << 8) | (key[34] & 0xFF);
                GovActionId id = new GovActionId(txHash, govIdx);

                GovActionRecord record = GovernanceCborCodec.decodeGovAction(it.value());
                result.put(id, record);
                it.next();
            }
        }
        return result;
    }

    public void removeProposal(GovActionId id, WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = govActionKey(id);
        byte[] prev = db.get(cfState, key);
        if (prev != null) {
            batch.delete(cfState, key);
            deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
        }
    }

    // ===== Vote operations =====

    public void storeVote(String proposalTxHash, int proposalIdx, int voterType, String voterHash,
                          int vote, WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = voteKey(proposalTxHash, proposalIdx, voterType, voterHash);
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeVote(vote);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    /** Get all votes for a proposal, grouped by voter type. */
    public Map<VoterKey, Integer> getVotesForProposal(String txHash, int govIdx) throws RocksDBException {
        Map<VoterKey, Integer> votes = new HashMap<>();
        byte[] prefix = voteKeyPrefix(txHash, govIdx);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(prefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 64 || !startsWith(key, prefix)) break;

                int voterType = key[35] & 0xFF;
                String voterHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 36, 64));
                int vote = GovernanceCborCodec.decodeVote(it.value());
                votes.put(new VoterKey(voterType, voterHash), vote);
                it.next();
            }
        }
        return votes;
    }

    /** Remove all votes for a proposal (when proposal is removed). */
    public void removeVotesForProposal(String txHash, int govIdx,
                                       WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] prefix = voteKeyPrefix(txHash, govIdx);
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(prefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (!startsWith(key, prefix)) break;
                byte[] prev = it.value();
                batch.delete(cfState, key);
                deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
                it.next();
            }
        }
    }

    public record VoterKey(int voterType, String voterHash) {}

    // ===== DRep State operations =====

    public void storeDRepState(int credType, String credHash, DRepStateRecord record,
                               WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = drepStateKey(credType, credHash);
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeDRepState(record);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Optional<DRepStateRecord> getDRepState(int credType, String credHash) throws RocksDBException {
        byte[] val = db.get(cfState, drepStateKey(credType, credHash));
        return val != null ? Optional.of(GovernanceCborCodec.decodeDRepState(val)) : Optional.empty();
    }

    /**
     * Iterate all DRep state entries (including inactive/deregistered DReps).
     * Inactive DReps are kept for v9 re-registration bug tracking.
     */
    public Map<CredentialKey, DRepStateRecord> getAllDRepStates() throws RocksDBException {
        return getDRepStates(false);
    }

    /**
     * Iterate only active (registered) DRep state entries.
     * Use this for DRep distribution calculation and vote tallying.
     */
    public Map<CredentialKey, DRepStateRecord> getActiveDRepStates() throws RocksDBException {
        return getDRepStates(true);
    }

    private Map<CredentialKey, DRepStateRecord> getDRepStates(boolean activeOnly) throws RocksDBException {
        Map<CredentialKey, DRepStateRecord> result = new LinkedHashMap<>();
        byte[] seekKey = new byte[]{PREFIX_DREP_STATE};

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 30 || key[0] != PREFIX_DREP_STATE) break;

                int credType = key[1] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, 30));
                DRepStateRecord record = GovernanceCborCodec.decodeDRepState(it.value());
                if (!activeOnly || record.active()) {
                    result.put(new CredentialKey(credType, credHash), record);
                }
                it.next();
            }
        }
        return result;
    }

    public void removeDRepState(int credType, String credHash,
                                WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = drepStateKey(credType, credHash);
        byte[] prev = db.get(cfState, key);
        if (prev != null) {
            batch.delete(cfState, key);
            deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
        }
    }

    public record CredentialKey(int credType, String hash) {}

    // ===== Committee Member operations =====

    public void storeCommitteeMember(int credType, String coldHash, CommitteeMemberRecord record,
                                     WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = committeeMemberKey(credType, coldHash);
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeCommitteeMember(record);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Optional<CommitteeMemberRecord> getCommitteeMember(int credType, String coldHash) throws RocksDBException {
        byte[] val = db.get(cfState, committeeMemberKey(credType, coldHash));
        return val != null ? Optional.of(GovernanceCborCodec.decodeCommitteeMember(val)) : Optional.empty();
    }

    /** Iterate all committee members. */
    public Map<CredentialKey, CommitteeMemberRecord> getAllCommitteeMembers() throws RocksDBException {
        Map<CredentialKey, CommitteeMemberRecord> result = new LinkedHashMap<>();
        byte[] seekKey = new byte[]{PREFIX_COMMITTEE_MEMBER};

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 30 || key[0] != PREFIX_COMMITTEE_MEMBER) break;

                int credType = key[1] & 0xFF;
                String coldHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 2, 30));
                CommitteeMemberRecord record = GovernanceCborCodec.decodeCommitteeMember(it.value());
                result.put(new CredentialKey(credType, coldHash), record);
                it.next();
            }
        }
        return result;
    }

    public void removeCommitteeMember(int credType, String coldHash,
                                      WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = committeeMemberKey(credType, coldHash);
        byte[] prev = db.get(cfState, key);
        if (prev != null) {
            batch.delete(cfState, key);
            deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
        }
    }

    /** Remove all committee members (used by NoConfidence enactment). */
    public void clearAllCommitteeMembers(WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] seekKey = new byte[]{PREFIX_COMMITTEE_MEMBER};
        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 2 || key[0] != PREFIX_COMMITTEE_MEMBER) break;
                byte[] prev = it.value();
                batch.delete(cfState, key);
                deltaOps.add(new DeltaOp(OP_DELETE, key, prev));
                it.next();
            }
        }
    }

    // ===== Committee Threshold =====

    /**
     * Store the committee quorum threshold as a rational number (numerator/denominator).
     * Updated by UpdateCommittee enactment or from Conway genesis.
     */
    public void storeCommitteeThreshold(BigInteger numerator, BigInteger denominator,
                                        WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = committeeThresholdKey();
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeCommitteeThreshold(numerator, denominator);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Optional<GovernanceCborCodec.CommitteeThreshold> getCommitteeThreshold() throws RocksDBException {
        byte[] val = db.get(cfState, committeeThresholdKey());
        return val != null ? Optional.of(GovernanceCborCodec.decodeCommitteeThreshold(val)) : Optional.empty();
    }

    // ===== Constitution =====

    public void storeConstitution(GovernanceCborCodec.ConstitutionRecord rec,
                                  WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = constitutionKey();
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeConstitution(rec);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Optional<GovernanceCborCodec.ConstitutionRecord> getConstitution() throws RocksDBException {
        byte[] val = db.get(cfState, constitutionKey());
        return val != null ? Optional.of(GovernanceCborCodec.decodeConstitution(val)) : Optional.empty();
    }

    // ===== Dormant Epochs =====

    public void storeDormantEpochs(Set<Integer> epochs,
                                   WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = dormantEpochsKey();
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeDormantEpochs(epochs);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Set<Integer> getDormantEpochs() throws RocksDBException {
        byte[] val = db.get(cfState, dormantEpochsKey());
        return val != null ? GovernanceCborCodec.decodeDormantEpochs(val) : new HashSet<>();
    }

    // ===== DRep Distribution Snapshot =====

    public void storeDRepDistEntry(int epoch, int credType, String drepHash, BigInteger stake,
                                   WriteBatch batch) throws RocksDBException {
        byte[] key = drepDistKey(epoch, credType, drepHash);
        byte[] val = GovernanceCborCodec.encodeDRepDistStake(stake);
        batch.put(cfState, key, val);
    }

    public Map<CredentialKey, BigInteger> getDRepDistribution(int epoch) throws RocksDBException {
        Map<CredentialKey, BigInteger> result = new LinkedHashMap<>();
        byte[] prefix = drepDistEpochPrefix(epoch);

        try (RocksIterator it = db.newIterator(cfState)) {
            it.seek(prefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 34 || !startsWith(key, prefix)) break;

                int credType = key[5] & 0xFF;
                String hash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 6, 34));
                BigInteger stake = GovernanceCborCodec.decodeDRepDistStake(it.value());
                result.put(new CredentialKey(credType, hash), stake);
                it.next();
            }
        }
        return result;
    }

    // ===== Epoch Donations =====

    public void accumulateDonation(int epoch, BigInteger amount,
                                   WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = epochDonationsKey(epoch);
        byte[] prev = db.get(cfState, key);
        BigInteger current = (prev != null) ? GovernanceCborCodec.decodeDonations(prev) : BigInteger.ZERO;
        BigInteger updated = current.add(amount);
        byte[] val = GovernanceCborCodec.encodeDonations(updated);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public BigInteger getEpochDonations(int epoch) throws RocksDBException {
        byte[] val = db.get(cfState, epochDonationsKey(epoch));
        return val != null ? GovernanceCborCodec.decodeDonations(val) : BigInteger.ZERO;
    }

    // ===== Last Enacted Action =====

    public void storeLastEnactedAction(GovActionType type, String txHash, int govActionIndex,
                                       WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = lastEnactedKey(type);
        byte[] prev = db.get(cfState, key);
        byte[] val = GovernanceCborCodec.encodeLastEnactedAction(txHash, govActionIndex);
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public Optional<GovernanceCborCodec.LastEnactedAction> getLastEnactedAction(GovActionType type) throws RocksDBException {
        byte[] val = db.get(cfState, lastEnactedKey(type));
        return val != null ? Optional.of(GovernanceCborCodec.decodeLastEnactedAction(val)) : Optional.empty();
    }

    // ===== Epoch Proposals Flag =====

    public void storeEpochHadActiveProposals(int epoch, boolean hadActive,
                                             WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        byte[] key = epochProposalsFlagKey(epoch);
        byte[] prev = db.get(cfState, key);
        byte[] val = new byte[]{(byte) (hadActive ? 1 : 0)};
        batch.put(cfState, key, val);
        deltaOps.add(new DeltaOp(OP_PUT, key, prev));
    }

    public boolean epochHadActiveProposals(int epoch) throws RocksDBException {
        byte[] val = db.get(cfState, epochProposalsFlagKey(epoch));
        return val != null && val[0] == 1;
    }

    // ===== Utility =====

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

}
