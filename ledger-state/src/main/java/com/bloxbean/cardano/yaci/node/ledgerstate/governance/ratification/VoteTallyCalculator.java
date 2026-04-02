package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.VoterType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore.CredentialKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch.DRepDistributionCalculator.DRepDistKey;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Set;
import java.util.*;

/**
 * Computes vote tallies for committee, DRep, and SPO voters.
 * Port of yaci-store VoteTallyCalculator.
 */
public class VoteTallyCalculator {

    // Vote values (matching GovernanceCborCodec encoding)
    public static final int VOTE_NO = 0;
    public static final int VOTE_YES = 1;
    public static final int VOTE_ABSTAIN = 2;

    // DRep type constants
    private static final int DREP_KEY = 0;
    private static final int DREP_SCRIPT = 1;
    private static final int DREP_ABSTAIN = 2;
    private static final int DREP_NO_CONF = 3;

    // ===== DRep Vote Tally =====

    /**
     * Compute DRep vote tallies for a proposal.
     *
     * @param votes          Votes for this proposal (voterKey → vote value)
     * @param drepDist       DRep distribution (drepKey → stake) — includes expired DReps
     * @param actionType     The governance action type (affects AlwaysNoConfidence behavior)
     * @param activeDRepKeys Set of DRep keys that are active (not expired). Only these count
     *                       as NO when they haven't voted. Expired DReps are excluded from tally.
     *                       Pass null to count ALL DReps (legacy behavior).
     * @return DRep tally result
     */
    public DRepTally computeDRepTally(Map<GovernanceStateStore.VoterKey, Integer> votes,
                                      Map<DRepDistKey, BigInteger> drepDist,
                                      GovActionType actionType,
                                      Set<DRepDistKey> activeDRepKeys) {
        BigInteger yesStake = BigInteger.ZERO;
        BigInteger noStake = BigInteger.ZERO;
        BigInteger abstainStake = BigInteger.ZERO;

        // Track which DReps have voted
        Set<String> votedDReps = new HashSet<>();

        // Process explicit votes from DRep voters
        for (var entry : votes.entrySet()) {
            GovernanceStateStore.VoterKey voterKey = entry.getKey();
            int voterType = voterKey.voterType();
            int vote = entry.getValue();

            // Only DRep voters
            if (voterType != VoterType.DREP_KEY_HASH.ordinal()
                    && voterType != VoterType.DREP_SCRIPT_HASH.ordinal()) continue;

            int drepType = (voterType == VoterType.DREP_KEY_HASH.ordinal()) ? DREP_KEY : DREP_SCRIPT;
            DRepDistKey drepKey = new DRepDistKey(drepType, voterKey.voterHash());
            BigInteger stake = drepDist.getOrDefault(drepKey, BigInteger.ZERO);

            switch (vote) {
                case VOTE_YES -> yesStake = yesStake.add(stake);
                case VOTE_NO -> noStake = noStake.add(stake);
                case VOTE_ABSTAIN -> abstainStake = abstainStake.add(stake);
            }
            votedDReps.add(drepType + ":" + voterKey.voterHash());
        }

        // AlwaysNoConfidence virtual DRep: YES for NoConfidence, NO for everything else
        BigInteger noConfStake = drepDist.getOrDefault(
                new DRepDistKey(DREP_NO_CONF, "no_confidence"), BigInteger.ZERO);
        if (actionType == GovActionType.NO_CONFIDENCE) {
            yesStake = yesStake.add(noConfStake);
        } else {
            noStake = noStake.add(noConfStake);
        }

        // AlwaysAbstain virtual DRep: counted as abstain (excluded from threshold)
        BigInteger abstainDRepStake = drepDist.getOrDefault(
                new DRepDistKey(DREP_ABSTAIN, "abstain"), BigInteger.ZERO);
        abstainStake = abstainStake.add(abstainDRepStake);

        // DReps that didn't vote: counted as NO (their stake reduces the yes ratio).
        // Only ACTIVE DReps count — expired DReps are excluded from the tally entirely
        // (matching Haskell/Amaru: st.is_active(epoch)).
        for (var drepEntry : drepDist.entrySet()) {
            DRepDistKey dk = drepEntry.getKey();
            if (dk.drepType() == DREP_ABSTAIN || dk.drepType() == DREP_NO_CONF) continue;
            // Skip expired DReps — they don't count in the tally
            if (activeDRepKeys != null && !activeDRepKeys.contains(dk)) continue;
            String drepId = dk.drepType() + ":" + dk.drepHash();
            if (!votedDReps.contains(drepId)) {
                noStake = noStake.add(drepEntry.getValue());
            }
        }

        return new DRepTally(yesStake, noStake, abstainStake);
    }

    // ===== SPO Vote Tally =====

    /**
     * Compute SPO vote tallies for a proposal.
     *
     * @param votes              Votes for this proposal
     * @param poolStakeDist      Pool → active stake distribution
     * @param poolDRepDelegation Pool reward account → DRep delegation type (for default votes)
     * @param actionType         Governance action type
     * @param isBootstrapPhase   Whether we're in bootstrap phase (affects non-voter default)
     * @return SPO tally result
     */
    public SPOTally computeSPOTally(Map<GovernanceStateStore.VoterKey, Integer> votes,
                                    Map<String, BigInteger> poolStakeDist,
                                    Map<String, Integer> poolDRepDelegation,
                                    GovActionType actionType,
                                    boolean isBootstrapPhase) {
        BigInteger yesStake = BigInteger.ZERO;
        BigInteger noStake = BigInteger.ZERO;
        BigInteger abstainStake = BigInteger.ZERO;
        BigInteger totalStake = BigInteger.ZERO;

        // Track which pools voted
        Set<String> votedPools = new HashSet<>();

        // Process explicit votes from SPO voters
        for (var entry : votes.entrySet()) {
            GovernanceStateStore.VoterKey voterKey = entry.getKey();
            if (voterKey.voterType() != VoterType.STAKING_POOL_KEY_HASH.ordinal()) continue;

            String poolHash = voterKey.voterHash();
            BigInteger stake = poolStakeDist.getOrDefault(poolHash, BigInteger.ZERO);
            int vote = entry.getValue();

            switch (vote) {
                case VOTE_YES -> yesStake = yesStake.add(stake);
                case VOTE_NO -> noStake = noStake.add(stake);
                case VOTE_ABSTAIN -> abstainStake = abstainStake.add(stake);
            }
            votedPools.add(poolHash);
        }

        // Process non-voting pools: default vote based on delegated-to DRep
        for (var poolEntry : poolStakeDist.entrySet()) {
            String poolHash = poolEntry.getKey();
            BigInteger stake = poolEntry.getValue();
            totalStake = totalStake.add(stake);

            if (votedPools.contains(poolHash)) continue;

            // Default vote based on pool's DRep delegation
            Integer drepType = poolDRepDelegation.get(poolHash);

            if (drepType != null && drepType == DREP_NO_CONF) {
                // Delegated to AlwaysNoConfidence:
                // YES for NoConfidence actions, NO for everything else (always NO, not abstain)
                if (actionType == GovActionType.NO_CONFIDENCE) {
                    yesStake = yesStake.add(stake);
                } else {
                    noStake = noStake.add(stake);
                }
            } else if (drepType != null && drepType == DREP_ABSTAIN) {
                // Delegated to AlwaysAbstain: always abstain
                abstainStake = abstainStake.add(stake);
            } else {
                // No DRep delegation or regular DRep — default behavior for non-voters
                if (isBootstrapPhase && actionType != GovActionType.HARD_FORK_INITIATION_ACTION) {
                    // Bootstrap: non-voters default to abstain (except for HardFork)
                    abstainStake = abstainStake.add(stake);
                } else {
                    // Post-bootstrap or HardFork: non-voters default to NO
                    noStake = noStake.add(stake);
                }
            }
        }

        return new SPOTally(yesStake, noStake, abstainStake, totalStake);
    }

    // ===== Committee Vote Tally =====

    /**
     * Compute committee vote tallies for a proposal.
     * Handles many-to-one cold-to-hot key mapping.
     *
     * @param votes   Votes for this proposal (only committee voter types)
     * @param members All committee members (credKey → record)
     * @param currentEpoch Current epoch (for expiry filtering)
     * @return Committee tally result
     */
    public CommitteeTally computeCommitteeTally(
            Map<GovernanceStateStore.VoterKey, Integer> votes,
            Map<CredentialKey, CommitteeMemberRecord> members,
            int currentEpoch) {

        int yesCount = 0;
        int noCount = 0;
        int abstainCount = 0;

        // Build hot-key → vote map from votes
        Map<String, Integer> hotKeyVotes = new HashMap<>();
        for (var entry : votes.entrySet()) {
            GovernanceStateStore.VoterKey voterKey = entry.getKey();
            int vType = voterKey.voterType();
            if (vType == VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH.ordinal()
                    || vType == VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH.ordinal()) {
                hotKeyVotes.put(voterKey.voterHash(), entry.getValue());
            }
        }

        // For each non-expired, non-resigned member, find their vote via hot key
        for (var memberEntry : members.entrySet()) {
            CommitteeMemberRecord member = memberEntry.getValue();

            // Skip expired members
            if (member.expiryEpoch() <= currentEpoch) continue;
            // Skip resigned members
            if (member.resigned()) continue;

            if (!member.hasHotKey()) {
                // No hot key authorized — member excluded from tally entirely.
                // Per Haskell spec, members without hot key authorization cannot participate
                // in voting and are not counted in the denominator.
                continue;
            }

            Integer vote = hotKeyVotes.get(member.hotHash());
            if (vote == null) {
                // Hot key didn't vote — counts as NO
                noCount++;
            } else {
                switch (vote) {
                    case VOTE_YES -> yesCount++;
                    case VOTE_NO -> noCount++;
                    case VOTE_ABSTAIN -> abstainCount++;
                }
            }
        }

        return new CommitteeTally(yesCount, noCount, abstainCount);
    }

    // ===== Threshold Checking =====

    /**
     * Check if DRep vote passes the threshold.
     * Formula: yes / (yes + no) >= threshold
     * Special: if (yes + no) == 0, auto-passes.
     */
    public static boolean drepThresholdMet(DRepTally tally, BigDecimal threshold) {
        BigInteger denominator = tally.yesStake.add(tally.noStake);
        if (denominator.signum() == 0) return true; // No votes → passes
        if (threshold.compareTo(BigDecimal.ZERO) == 0) return true; // Threshold 0 → always passes (bootstrap)

        BigDecimal ratio = new BigDecimal(tally.yesStake)
                .divide(new BigDecimal(denominator), MathContext.DECIMAL128);
        return ratio.compareTo(threshold) >= 0;
    }

    /**
     * Check if SPO vote passes the threshold.
     * Formula: yes / (total - abstain) >= threshold
     * Special: if total == 0 or abstain == total, auto-passes.
     */
    public static boolean spoThresholdMet(SPOTally tally, BigDecimal threshold) {
        BigInteger denominator = tally.totalStake.subtract(tally.abstainStake);
        if (denominator.signum() <= 0) return true;

        BigDecimal ratio = new BigDecimal(tally.yesStake)
                .divide(new BigDecimal(denominator), MathContext.DECIMAL128);
        return ratio.compareTo(threshold) >= 0;
    }

    /**
     * Check if committee vote passes the threshold.
     * Formula: yes / (yes + no) >= threshold
     * "Did not vote" already counted as NO in the tally.
     */
    public static boolean committeeThresholdMet(CommitteeTally tally, BigDecimal threshold) {
        int denominator = tally.yesCount + tally.noCount;
        if (denominator == 0) return false; // No eligible committee members → no quorum → fail
        if (threshold.compareTo(BigDecimal.ZERO) == 0) return true;

        BigDecimal ratio = BigDecimal.valueOf(tally.yesCount)
                .divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128);
        return ratio.compareTo(threshold) >= 0;
    }

    // ===== Result Records =====

    public record DRepTally(BigInteger yesStake, BigInteger noStake, BigInteger abstainStake) {}
    public record SPOTally(BigInteger yesStake, BigInteger noStake, BigInteger abstainStake, BigInteger totalStake) {}
    public record CommitteeTally(int yesCount, int noCount, int abstainCount) {}
}
