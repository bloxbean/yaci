package com.bloxbean.cardano.yaci.node.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.*;
import com.bloxbean.cardano.yaci.core.model.governance.actions.*;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;

/**
 * Processes governance-relevant data from blocks: proposals, votes, DRep certs,
 * committee certs, and donations. Called from DefaultAccountStateStore.applyBlock().
 */
public class GovernanceBlockProcessor {
    private static final Logger log = LoggerFactory.getLogger(GovernanceBlockProcessor.class);

    private final GovernanceStateStore governanceStore;

    /** Get current consecutive dormant epoch count for v10 DRep registration */
    private int getDormantCount() {
        try {
            return governanceStore.getDormantEpochs().size();
        } catch (Exception e) {
            return 0;
        }
    }
    private final EpochParamProvider paramProvider;

    public GovernanceBlockProcessor(GovernanceStateStore governanceStore, EpochParamProvider paramProvider) {
        this.governanceStore = governanceStore;
        this.paramProvider = paramProvider;
    }

    /**
     * Process a block for governance-relevant data: proposals, votes, donations.
     * DRep certs and committee certs are handled separately via processDRep* and processCommittee* methods,
     * called from DefaultAccountStateStore.processCertificate() for dual-write.
     */
    public void processBlock(Block block, long slot, int currentEpoch,
                             WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        List<Integer> invList = block.getInvalidTransactions();
        Set<Integer> invalidIdx = (invList != null) ? new HashSet<>(invList) : Collections.emptySet();
        List<TransactionBody> txs = block.getTransactionBodies();

        if (txs == null) return;

        // Accumulate donations across all txs in the block first, then write once.
        // This avoids WriteBatch visibility issues when multiple txs donate in the same block
        // (db.get reads committed state, not in-flight batch).
        BigInteger blockDonations = BigInteger.ZERO;

        for (int txIdx = 0; txIdx < txs.size(); txIdx++) {
            if (invalidIdx.contains(txIdx)) continue;
            TransactionBody tx = txs.get(txIdx);

            // 1. Process proposal submissions
            processProposals(tx, slot, currentEpoch, batch, deltaOps);

            // 2. Process votes + track DRep interactions (single pass)
            processVotesAndTrackInteractions(tx, currentEpoch, batch, deltaOps);

            // 3. Accumulate donations
            BigInteger donation = tx.getDonation();
            if (donation != null && donation.signum() > 0) {
                blockDonations = blockDonations.add(donation);
            }
        }

        // Write accumulated donations once per block
        if (blockDonations.signum() > 0) {
            governanceStore.accumulateDonation(currentEpoch, blockDonations, batch, deltaOps);
            log.debug("Accumulated block donations {} in epoch {}", blockDonations, currentEpoch);
        }
    }

    // ===== Proposal Processing =====

    private void processProposals(TransactionBody tx, long slot, int currentEpoch,
                                  WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        List<ProposalProcedure> proposals = tx.getProposalProcedures();
        if (proposals == null || proposals.isEmpty()) return;

        int govActionLifetime = paramProvider.getGovActionLifetime(currentEpoch);

        for (int idx = 0; idx < proposals.size(); idx++) {
            ProposalProcedure pp = proposals.get(idx);
            GovAction action = pp.getGovAction();
            if (action == null) continue;

            // Extract prevGovActionId from action types that support it
            String prevTxHash = null;
            Integer prevIndex = null;
            GovActionId prevId = extractPrevGovActionId(action);
            if (prevId != null) {
                prevTxHash = prevId.getTransactionId();
                prevIndex = prevId.getGov_action_index();
            }

            GovActionRecord record = new GovActionRecord(
                    pp.getDeposit() != null ? pp.getDeposit() : BigInteger.ZERO,
                    pp.getRewardAccount() != null ? pp.getRewardAccount() : "",
                    currentEpoch,
                    currentEpoch + govActionLifetime,
                    action.getType(),
                    prevTxHash,
                    prevIndex,
                    action, // fully parsed GovAction object (for enactment)
                    slot
            );

            GovActionId id = new GovActionId(tx.getTxHash(), idx);
            governanceStore.storeProposal(id, record, batch, deltaOps);

            // Store permanent proposal submission metadata (for v9 DRep bonus calculation)
            governanceStore.storeProposalSubmission(slot, currentEpoch, govActionLifetime, batch, deltaOps);

            log.info("GOV_PROPOSAL: stored {}/{} type={} epoch={} expiresAfter={}",
                    tx.getTxHash().substring(0, 8), idx, action.getType(),
                    currentEpoch, record.expiresAfterEpoch());
        }
    }

    private GovActionId extractPrevGovActionId(GovAction action) {
        return switch (action) {
            case ParameterChangeAction a -> a.getGovActionId();
            case HardForkInitiationAction a -> a.getGovActionId();
            case NoConfidence a -> a.getGovActionId();
            case UpdateCommittee a -> a.getGovActionId();
            case NewConstitution a -> a.getGovActionId();
            default -> null; // TreasuryWithdrawals, InfoAction don't have prevGovActionId
        };
    }

    // ===== Vote Processing + DRep Interaction Tracking (single pass) =====

    private void processVotesAndTrackInteractions(TransactionBody tx, int currentEpoch,
                                                  WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        VotingProcedures vp = tx.getVotingProcedures();
        if (vp == null || vp.getVoting() == null) return;

        // Track which DReps we've already updated this tx to avoid redundant writes
        Set<String> updatedDReps = new HashSet<>();

        for (var voterEntry : vp.getVoting().entrySet()) {
            Voter voter = voterEntry.getKey();
            Map<GovActionId, VotingProcedure> votes = voterEntry.getValue();

            if (votes == null) continue;

            // Store each vote record
            for (var voteEntry : votes.entrySet()) {
                GovActionId actionId = voteEntry.getKey();
                VotingProcedure procedure = voteEntry.getValue();

                int voteValue = procedure.getVote().ordinal(); // NO=0, YES=1, ABSTAIN=2

                governanceStore.storeVote(
                        actionId.getTransactionId(),
                        actionId.getGov_action_index(),
                        voter.getType().ordinal(),
                        voter.getHash(),
                        voteValue,
                        batch, deltaOps
                );
            }

            // Track DRep interaction (lastInteractionEpoch) — once per DRep per tx
            VoterType vType = voter.getType();
            if (vType == VoterType.DREP_KEY_HASH || vType == VoterType.DREP_SCRIPT_HASH) {
                int credType = (vType == VoterType.DREP_KEY_HASH) ? 0 : 1;
                String drepKey = credType + ":" + voter.getHash();

                if (!updatedDReps.contains(drepKey)) {
                    updatedDReps.add(drepKey);
                    Optional<DRepStateRecord> existing = governanceStore.getDRepState(credType, voter.getHash());
                    if (existing.isPresent() && existing.get().active()) {
                        DRepStateRecord updated = existing.get().withLastInteraction(currentEpoch);
                        governanceStore.storeDRepState(credType, voter.getHash(), updated, batch, deltaOps);
                    }
                }
            }
        }
    }

    // ===== DRep Certificate Processing (called from DefaultAccountStateStore.processCertificate()) =====

    /**
     * Process DRep registration — create governance-specific DRepStateRecord.
     * Called alongside the existing PREFIX_DREP_REG write in DefaultAccountStateStore.
     */
    public void processDRepRegistration(RegDrepCert cert, long slot, int currentEpoch,
                                        WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int credType = credTypeFromCert(cert.getDrepCredential());
        String hash = cert.getDrepCredential().getHash();
        BigInteger deposit = cert.getCoin() != null ? cert.getCoin() : BigInteger.ZERO;

        int protocolVersion = paramProvider.getProtocolMajor(currentEpoch);
        int drepActivity = paramProvider.getDRepActivity(currentEpoch);

        // Check for previous deregistration (needed for v9 bonus bug)
        Optional<DRepStateRecord> prevState = governanceStore.getDRepState(credType, hash);
        Long previousDeregSlot = null;
        if (prevState.isPresent()) {
            // Re-registration: the previous state's registered slot serves as the deregistration reference
            // This handles the v9 bug where re-registration after deregistration affects bonus calculation
            previousDeregSlot = prevState.get().previousDeregistrationSlot();
        }

        String anchorUrl = null;
        String anchorHash = null;
        if (cert.getAnchor() != null) {
            anchorUrl = cert.getAnchor().getAnchor_url();
            anchorHash = cert.getAnchor().getAnchor_data_hash();
        }

        DRepStateRecord record = new DRepStateRecord(
                deposit,
                anchorUrl,
                anchorHash,
                currentEpoch,
                null, // lastInteractionEpoch — none yet
                currentEpoch + drepActivity, // initial expiry (updated at epoch boundary by DRepExpiryCalculator)
                true,
                slot,
                protocolVersion,
                previousDeregSlot
        );

        governanceStore.storeDRepState(credType, hash, record, batch, deltaOps);
        log.debug("DRep registered: credType={} hash={} epoch={} protocolVer={}",
                credType, hash.substring(0, Math.min(8, hash.length())), currentEpoch, protocolVersion);
    }

    /**
     * Process DRep deregistration — record the deregistration slot for v9 bug tracking,
     * then remove the governance DRep state.
     */
    public void processDRepDeregistration(UnregDrepCert cert, long slot,
                                          WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int credType = credTypeFromCert(cert.getDrepCredential());
        String hash = cert.getDrepCredential().getHash();

        // Store the deregistration slot in case of future re-registration (v9 bug)
        // We store a minimal record with the deregistration slot so that if re-registered,
        // we can track previousDeregistrationSlot
        Optional<DRepStateRecord> existing = governanceStore.getDRepState(credType, hash);
        if (existing.isPresent()) {
            // Update the record to mark deregistration slot before removing
            DRepStateRecord deregRecord = new DRepStateRecord(
                    existing.get().deposit(),
                    existing.get().anchorUrl(),
                    existing.get().anchorHash(),
                    existing.get().registeredAtEpoch(),
                    existing.get().lastInteractionEpoch(),
                    existing.get().expiryEpoch(),
                    false, // no longer active
                    existing.get().registeredAtSlot(),
                    existing.get().protocolVersionAtRegistration(),
                    slot // THIS deregistration becomes the previous deregistration for any future re-reg
            );
            // Keep the record (don't delete) so re-registration can read previousDeregistrationSlot
            governanceStore.storeDRepState(credType, hash, deregRecord, batch, deltaOps);
        }
    }

    /**
     * Process DRep update — update anchor and track as interaction.
     */
    public void processDRepUpdate(UpdateDrepCert cert, int currentEpoch,
                                  WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int credType = credTypeFromCert(cert.getDrepCredential());
        String hash = cert.getDrepCredential().getHash();

        Optional<DRepStateRecord> existing = governanceStore.getDRepState(credType, hash);
        if (existing.isPresent()) {
            String anchorUrl = null;
            String anchorHash = null;
            if (cert.getAnchor() != null) {
                anchorUrl = cert.getAnchor().getAnchor_url();
                anchorHash = cert.getAnchor().getAnchor_data_hash();
            }

            DRepStateRecord updated = existing.get()
                    .withAnchor(anchorUrl, anchorHash)
                    .withLastInteraction(currentEpoch);
            governanceStore.storeDRepState(credType, hash, updated, batch, deltaOps);
        }
    }

    // ===== Committee Certificate Processing =====

    /**
     * Process committee hot key authorization — create/update CommitteeMemberRecord.
     * Per Haskell spec, hot key authorizations are stored independently of committee
     * membership. A credential may authorize a hot key before being added to the committee.
     * We always store the hot key so it's available when the member is later enrolled.
     */
    public void processCommitteeHotKeyAuth(AuthCommitteeHotCert cert,
                                           WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int coldCt = credTypeFromCert(cert.getCommitteeColdCredential());
        String coldHash = cert.getCommitteeColdCredential().getHash();
        int hotCt = credTypeFromCert(cert.getCommitteeHotCredential());
        String hotHash = cert.getCommitteeHotCredential().getHash();

        Optional<CommitteeMemberRecord> existing = governanceStore.getCommitteeMember(coldCt, coldHash);
        if (existing.isPresent()) {
            CommitteeMemberRecord updated = existing.get().withHotKey(hotCt, hotHash);
            governanceStore.storeCommitteeMember(coldCt, coldHash, updated, batch, deltaOps);
        } else {
            // Member not yet in committee — store placeholder with hot key so it's available
            // when UpdateCommittee enactment later adds this member.
            // expiryEpoch=0 ensures this placeholder is never counted as active.
            CommitteeMemberRecord placeholder = new CommitteeMemberRecord(hotCt, hotHash, 0, false);
            governanceStore.storeCommitteeMember(coldCt, coldHash, placeholder, batch, deltaOps);
            log.debug("Committee hot key auth stored for future member {}:{}",
                    coldCt, coldHash.substring(0, Math.min(8, coldHash.length())));
        }
    }

    /**
     * Process committee resignation.
     */
    public void processCommitteeResignation(ResignCommitteeColdCert cert,
                                            WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        int coldCt = credTypeFromCert(cert.getCommitteeColdCredential());
        String coldHash = cert.getCommitteeColdCredential().getHash();

        Optional<CommitteeMemberRecord> existing = governanceStore.getCommitteeMember(coldCt, coldHash);
        if (existing.isPresent()) {
            governanceStore.storeCommitteeMember(coldCt, coldHash, existing.get().asResigned(), batch, deltaOps);
        }
    }

    // ===== Utility =====

    private static int credTypeFromCert(com.bloxbean.cardano.yaci.core.model.Credential cred) {
        return cred.getType() == com.bloxbean.cardano.yaci.core.model.certs.StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }
}
