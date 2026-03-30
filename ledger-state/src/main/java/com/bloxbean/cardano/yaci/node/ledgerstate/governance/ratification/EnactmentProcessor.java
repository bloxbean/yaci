package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.*;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.EpochParamTracker;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceCborCodec;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;

/**
 * Applies the effects of ratified governance actions (ENACT rule).
 * <p>
 * Ratified actions are enacted at the epoch boundary following ratification.
 * Each action type has specific state mutations.
 */
public class EnactmentProcessor {
    private static final Logger log = LoggerFactory.getLogger(EnactmentProcessor.class);

    private final GovernanceStateStore governanceStore;
    private final EpochParamTracker paramTracker;

    public EnactmentProcessor(GovernanceStateStore governanceStore, EpochParamTracker paramTracker) {
        this.governanceStore = governanceStore;
        this.paramTracker = paramTracker;
    }

    /**
     * Enact a ratified governance action and update the last-enacted tracker.
     *
     * @param id       The proposal ID
     * @param proposal The proposal record
     * @param epoch    The epoch at which enactment takes effect
     * @param batch    WriteBatch for atomic writes
     * @param deltaOps Delta ops for rollback
     * @return Treasury delta from this enactment (negative = treasury decreases, e.g., withdrawals)
     */
    public BigInteger enact(GovActionId id, GovActionRecord proposal, int epoch,
                            WriteBatch batch, List<DeltaOp> deltaOps) throws RocksDBException {
        GovActionType type = proposal.actionType();
        BigInteger treasuryDelta = BigInteger.ZERO;

        switch (type) {
            case PARAMETER_CHANGE_ACTION -> {
                if (proposal.govAction() instanceof ParameterChangeAction pca
                        && pca.getProtocolParamUpdate() != null && paramTracker != null) {
                    paramTracker.applyEnactedParamChange(epoch, pca.getProtocolParamUpdate());
                }
                log.info("Enacted ParameterChange for epoch {} from {}/{}", epoch,
                        id.getTransactionId().substring(0, 8), id.getGov_action_index());
            }
            case HARD_FORK_INITIATION_ACTION -> {
                if (proposal.govAction() instanceof HardForkInitiationAction hf
                        && hf.getProtocolVersion() != null && paramTracker != null) {
                    var ppu = com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate.builder()
                            .protocolMajorVer((int) hf.getProtocolVersion().get_1())
                            .protocolMinorVer((int) hf.getProtocolVersion().get_2())
                            .build();
                    paramTracker.applyEnactedParamChange(epoch, ppu);
                }
                log.info("Enacted HardForkInitiation for epoch {} from {}/{}", epoch,
                        id.getTransactionId().substring(0, 8), id.getGov_action_index());
            }
            case TREASURY_WITHDRAWALS_ACTION -> {
                // Treasury withdrawals reduce treasury and credit reward accounts.
                // The total withdrawal amount is computed here; actual reward account credits
                // are processed by GovernanceEpochProcessor which has access to the account store.
                if (proposal.govAction() instanceof TreasuryWithdrawalsAction twa
                        && twa.getWithdrawals() != null) {
                    for (BigInteger amount : twa.getWithdrawals().values()) {
                        treasuryDelta = treasuryDelta.subtract(amount);
                    }
                }
                log.info("Enacted TreasuryWithdrawals from {}/{}, treasuryDelta={}",
                        id.getTransactionId().substring(0, 8), id.getGov_action_index(), treasuryDelta);
            }
            case NO_CONFIDENCE -> {
                governanceStore.clearAllCommitteeMembers(batch, deltaOps);
                log.info("Enacted NoConfidence — committee cleared");
            }
            case UPDATE_COMMITTEE -> {
                if (proposal.govAction() instanceof UpdateCommittee uc) {
                    enactUpdateCommittee(uc, batch, deltaOps);
                }
                log.info("Enacted UpdateCommittee from {}/{}", id.getTransactionId().substring(0, 8),
                        id.getGov_action_index());
            }
            case NEW_CONSTITUTION -> {
                if (proposal.govAction() instanceof NewConstitution nc && nc.getConstitution() != null) {
                    var anchor = nc.getConstitution().getAnchor();
                    String scriptHash = nc.getConstitution().getScripthash();
                    governanceStore.storeConstitution(new GovernanceCborCodec.ConstitutionRecord(
                            anchor != null ? anchor.getAnchor_url() : null,
                            anchor != null ? anchor.getAnchor_data_hash() : null,
                            scriptHash), batch, deltaOps);
                }
                log.info("Enacted NewConstitution from {}/{}", id.getTransactionId().substring(0, 8),
                        id.getGov_action_index());
            }
            case INFO_ACTION -> {
                // No-op — should never be enacted
            }
        }

        // Track as last enacted action for this purpose type
        GovActionType purposeType = ProposalDropService.getPurposeType(type);
        if (purposeType != null) {
            governanceStore.storeLastEnactedAction(purposeType, id.getTransactionId(),
                    id.getGov_action_index(), batch, deltaOps);
        }

        return treasuryDelta;
    }

    private void enactUpdateCommittee(UpdateCommittee uc, WriteBatch batch,
                                      List<DeltaOp> deltaOps) throws RocksDBException {
        // Remove members
        if (uc.getMembersForRemoval() != null) {
            for (Credential cred : uc.getMembersForRemoval()) {
                int ct = credTypeFromModel(cred);
                governanceStore.removeCommitteeMember(ct, cred.getHash(), batch, deltaOps);
            }
        }

        // Add new members with term epochs
        if (uc.getNewMembersAndTerms() != null) {
            for (var entry : uc.getNewMembersAndTerms().entrySet()) {
                Credential cred = entry.getKey();
                int expiryEpoch = entry.getValue();
                int ct = credTypeFromModel(cred);
                CommitteeMemberRecord record = CommitteeMemberRecord.noHotKey(expiryEpoch);
                governanceStore.storeCommitteeMember(ct, cred.getHash(), record, batch, deltaOps);
            }
        }

        // Update committee threshold
        if (uc.getThreshold() != null) {
            BigInteger num = uc.getThreshold().getNumerator();
            BigInteger den = uc.getThreshold().getDenominator();
            if (num != null && den != null) {
                governanceStore.storeCommitteeThreshold(num, den, batch, deltaOps);
            }
        }
    }

    private static int credTypeFromModel(Credential cred) {
        return cred.getType() == com.bloxbean.cardano.yaci.core.model.certs.StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }
}
