package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.RatificationResult;

import java.util.*;

/**
 * Determines which proposals to drop (remove) after ratification/expiry.
 * When a proposal is ratified, its siblings (same purpose, same prevActionId) and
 * their descendants are dropped. When a proposal expires, its descendants are dropped.
 */
public class ProposalDropService {

    /**
     * Compute proposals to drop based on ratification and expiry results.
     *
     * @param results         Ratification results for all proposals
     * @param activeProposals All currently active proposals
     * @return Set of GovActionIds to drop (remove from active, refund deposits)
     */
    public Set<GovActionId> computeProposalsToDrop(
            List<RatificationResult> results,
            Map<GovActionId, GovActionRecord> activeProposals) {

        Set<GovActionId> toDrop = new LinkedHashSet<>();

        for (RatificationResult result : results) {
            if (result.isRatified()) {
                // Drop siblings (same purpose, same prevActionId, not this proposal)
                Set<GovActionId> siblings = findSiblings(result.govActionId(), result.proposal(), activeProposals);
                toDrop.addAll(siblings);

                // Drop descendants of each sibling
                for (GovActionId sibling : siblings) {
                    GovActionRecord siblingRecord = activeProposals.get(sibling);
                    if (siblingRecord != null) {
                        toDrop.addAll(findDescendants(sibling, siblingRecord, activeProposals));
                    }
                }
            } else if (result.isExpired()) {
                // Drop descendants of expired proposal
                toDrop.addAll(findDescendants(result.govActionId(), result.proposal(), activeProposals));
            }
        }

        // Don't drop proposals that were already ratified or expired in this round
        for (RatificationResult result : results) {
            if (result.isRatified() || result.isExpired()) {
                toDrop.remove(result.govActionId());
            }
        }

        return toDrop;
    }

    /**
     * Find sibling proposals: same purpose, same prevGovActionId, different proposal.
     */
    Set<GovActionId> findSiblings(GovActionId proposalId, GovActionRecord proposal,
                                   Map<GovActionId, GovActionRecord> activeProposals) {
        Set<GovActionId> siblings = new LinkedHashSet<>();
        GovActionType purpose = getPurposeType(proposal.actionType());
        if (purpose == null) return siblings; // No purpose chain for this type

        for (var entry : activeProposals.entrySet()) {
            GovActionId otherId = entry.getKey();
            GovActionRecord other = entry.getValue();

            if (otherId.equals(proposalId)) continue;
            if (getPurposeType(other.actionType()) != purpose) continue;

            // Same prevGovActionId?
            if (samePrevAction(proposal, other)) {
                siblings.add(otherId);
            }
        }
        return siblings;
    }

    /**
     * Find all descendants of a proposal via BFS through the prevGovActionId chain.
     */
    Set<GovActionId> findDescendants(GovActionId rootId, GovActionRecord root,
                                      Map<GovActionId, GovActionRecord> activeProposals) {
        Set<GovActionId> descendants = new LinkedHashSet<>();
        GovActionType purpose = getPurposeType(root.actionType());
        if (purpose == null) return descendants;

        Queue<GovActionId> queue = new ArrayDeque<>();
        queue.add(rootId);

        while (!queue.isEmpty()) {
            GovActionId current = queue.poll();

            // Find children: proposals whose prevGovActionId == current
            for (var entry : activeProposals.entrySet()) {
                GovActionId otherId = entry.getKey();
                GovActionRecord other = entry.getValue();

                if (getPurposeType(other.actionType()) != purpose) continue;
                if (other.prevActionTxHash() != null
                        && other.prevActionTxHash().equals(current.getTransactionId())
                        && other.prevActionIndex() != null
                        && other.prevActionIndex().equals(current.getGov_action_index())
                        && !descendants.contains(otherId)) {
                    descendants.add(otherId);
                    queue.add(otherId);
                }
            }
        }

        return descendants;
    }

    /**
     * Map action types to purpose groups.
     * NO_CONFIDENCE and UPDATE_COMMITTEE share the "Committee" purpose.
     * TREASURY_WITHDRAWALS and INFO_ACTION have no purpose chain (null).
     */
    static GovActionType getPurposeType(GovActionType type) {
        return switch (type) {
            case NO_CONFIDENCE, UPDATE_COMMITTEE -> GovActionType.UPDATE_COMMITTEE;
            case PARAMETER_CHANGE_ACTION -> GovActionType.PARAMETER_CHANGE_ACTION;
            case HARD_FORK_INITIATION_ACTION -> GovActionType.HARD_FORK_INITIATION_ACTION;
            case NEW_CONSTITUTION -> GovActionType.NEW_CONSTITUTION;
            case TREASURY_WITHDRAWALS_ACTION, INFO_ACTION -> null;
        };
    }

    private static boolean samePrevAction(GovActionRecord a, GovActionRecord b) {
        String aPrev = a.prevActionTxHash();
        String bPrev = b.prevActionTxHash();

        if (aPrev == null && bPrev == null) return true;
        if (aPrev == null || bPrev == null) return false;
        return aPrev.equals(bPrev)
                && Objects.equals(a.prevActionIndex(), b.prevActionIndex());
    }
}
