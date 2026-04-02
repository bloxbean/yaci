package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.GovActionRecord;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Pre-computed input for ratification evaluation of a single proposal.
 * Contains the proposal metadata and pre-aggregated vote tallies per body.
 * <p>
 * This separates vote aggregation (done by VoteTallyCalculator) from
 * ratification evaluation (done by RatificationEngine), making the engine
 * fully stateless and testable.
 *
 * @param id                  Proposal governance action ID
 * @param proposal            Proposal record (type, lifecycle, prev-action)
 * @param drepTally           Pre-computed DRep vote tally (active DReps only)
 * @param committeeTally      Pre-computed committee vote tally
 * @param spoTally            Pre-computed SPO vote tally
 * @param drepThreshold       DRep threshold for this specific proposal (per-group for ParameterChange)
 * @param committeeThreshold  Committee threshold
 * @param spoThreshold        SPO threshold (0 if not required)
 * @param treasuryBalance     Current treasury (for TreasuryWithdrawal checks)
 */
public record ProposalEvaluationInput(
        GovActionId id,
        GovActionRecord proposal,
        VoteTallyCalculator.DRepTally drepTally,
        VoteTallyCalculator.CommitteeTally committeeTally,
        VoteTallyCalculator.SPOTally spoTally,
        BigDecimal drepThreshold,
        BigDecimal committeeThreshold,
        BigDecimal spoThreshold,
        BigInteger treasuryBalance
) {}
