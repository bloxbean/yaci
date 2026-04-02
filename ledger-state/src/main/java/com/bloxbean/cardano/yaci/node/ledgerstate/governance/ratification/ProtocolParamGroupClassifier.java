package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies protocol parameter updates into parameter groups (network, economic, technical,
 * governance, security) following the Cardano Conway spec.
 * <p>
 * Reference: Haskell cardano-ledger Conway/PParams.hs, yaci-store ProtocolParamUtil.
 */
public class ProtocolParamGroupClassifier {

    public enum ParamGroup { NETWORK, ECONOMIC, TECHNICAL, GOVERNANCE, SECURITY }

    /**
     * Determine which parameter groups are affected by a ProtocolParamUpdate.
     * A group is affected if ANY field in that group has a non-null value.
     */
    public static List<ParamGroup> getAffectedGroups(ProtocolParamUpdate params) {
        List<ParamGroup> groups = new ArrayList<>();

        if (hasAny(params.getMinFeeA(), params.getMinFeeB(), params.getKeyDeposit(), params.getPoolDeposit(),
                params.getMinPoolCost(), params.getPriceMem(), params.getPriceStep(), params.getAdaPerUtxoByte(),
                params.getExpansionRate(), params.getTreasuryGrowthRate(), params.getMinFeeRefScriptCostPerByte())) {
            groups.add(ParamGroup.ECONOMIC);
        }

        if (hasAny(params.getMaxBlockSize(), params.getMaxTxSize(), params.getMaxBlockHeaderSize(),
                params.getMaxValSize(), params.getMaxTxExMem(), params.getMaxTxExSteps(),
                params.getMaxBlockExMem(), params.getMaxBlockExSteps(), params.getMaxCollateralInputs())) {
            groups.add(ParamGroup.NETWORK);
        }

        if (hasAny(params.getNOpt(), params.getPoolPledgeInfluence(), params.getCostModels(),
                params.getCollateralPercent(), params.getMaxEpoch())) {
            groups.add(ParamGroup.TECHNICAL);
        }

        if (hasAny(params.getPoolVotingThresholds(), params.getDrepVotingThresholds(), params.getCommitteeMinSize(),
                params.getCommitteeMaxTermLength(), params.getGovActionLifetime(), params.getGovActionDeposit(),
                params.getDrepDeposit(), params.getDrepActivity())) {
            groups.add(ParamGroup.GOVERNANCE);
        }

        if (hasAny(params.getMaxBlockSize(), params.getMaxTxSize(), params.getMaxBlockHeaderSize(),
                params.getMaxValSize(), params.getMaxBlockExMem(), params.getMaxBlockExSteps(), params.getMinFeeA(),
                params.getMinFeeB(), params.getGovActionDeposit(), params.getMinFeeRefScriptCostPerByte(),
                params.getAdaPerUtxoByte())) {
            groups.add(ParamGroup.SECURITY);
        }

        return groups;
    }

    /**
     * Compute the DRep threshold for a ParameterChange proposal.
     * Takes the MAX threshold across all affected non-security groups.
     * DReps don't vote on security-only changes.
     *
     * @param affectedGroups Groups affected by the parameter change
     * @param protocolParams Current epoch's resolved protocol parameters (with thresholds)
     * @return The DRep threshold, or 0 if no DRep vote required
     */
    public static BigDecimal computeDRepThreshold(List<ParamGroup> affectedGroups,
                                                  ProtocolParamUpdate protocolParams) {
        BigDecimal maxThreshold = BigDecimal.ZERO;
        var drepThresholds = protocolParams.getDrepVotingThresholds();
        if (drepThresholds == null) return maxThreshold;

        for (ParamGroup group : affectedGroups) {
            BigDecimal t = switch (group) {
                case NETWORK -> ratioToBigDecimal(drepThresholds.getDvtPPNetworkGroup());
                case ECONOMIC -> ratioToBigDecimal(drepThresholds.getDvtPPEconomicGroup());
                case TECHNICAL -> ratioToBigDecimal(drepThresholds.getDvtPPTechnicalGroup());
                case GOVERNANCE -> ratioToBigDecimal(drepThresholds.getDvtPPGovGroup());
                case SECURITY -> BigDecimal.ZERO; // DReps don't vote on security params
            };
            if (t.compareTo(maxThreshold) > 0) maxThreshold = t;
        }
        return maxThreshold;
    }

    /**
     * Check if SPO voting is required for a ParameterChange.
     * SPOs only vote on changes that affect the security group.
     */
    public static boolean isSpoVotingRequired(List<ParamGroup> affectedGroups) {
        return affectedGroups.contains(ParamGroup.SECURITY);
    }

    /**
     * Get the SPO threshold for security-group parameter changes.
     */
    public static BigDecimal computeSpoThreshold(ProtocolParamUpdate protocolParams) {
        var poolThresholds = protocolParams.getPoolVotingThresholds();
        if (poolThresholds == null) return new BigDecimal("0.51"); // default
        return ratioToBigDecimal(poolThresholds.getPvtPPSecurityGroup());
    }

    /**
     * Get DRep thresholds for non-ParameterChange action types from protocol params.
     */
    public static BigDecimal getDRepThresholdForAction(String actionType, ProtocolParamUpdate protocolParams) {
        var dt = protocolParams.getDrepVotingThresholds();
        if (dt == null) return new BigDecimal("0.67"); // fallback
        return switch (actionType) {
            case "NO_CONFIDENCE" -> ratioToBigDecimal(dt.getDvtMotionNoConfidence());
            case "UPDATE_COMMITTEE" -> ratioToBigDecimal(dt.getDvtCommitteeNormal());
            case "NEW_CONSTITUTION" -> ratioToBigDecimal(dt.getDvtUpdateToConstitution());
            case "HARD_FORK_INITIATION_ACTION" -> ratioToBigDecimal(dt.getDvtHardForkInitiation());
            case "TREASURY_WITHDRAWALS_ACTION" -> ratioToBigDecimal(dt.getDvtTreasuryWithdrawal());
            default -> new BigDecimal("0.67");
        };
    }

    /**
     * Get SPO thresholds for non-ParameterChange action types from protocol params.
     */
    public static BigDecimal getSpoThresholdForAction(String actionType, ProtocolParamUpdate protocolParams) {
        var pt = protocolParams.getPoolVotingThresholds();
        if (pt == null) return new BigDecimal("0.51"); // fallback
        return switch (actionType) {
            case "NO_CONFIDENCE" -> ratioToBigDecimal(pt.getPvtMotionNoConfidence());
            case "UPDATE_COMMITTEE" -> ratioToBigDecimal(pt.getPvtCommitteeNormal());
            case "HARD_FORK_INITIATION_ACTION" -> ratioToBigDecimal(pt.getPvtHardForkInitiation());
            default -> BigDecimal.ZERO; // SPO doesn't vote on other types
        };
    }

    public static BigDecimal ratioToBigDecimal(com.bloxbean.cardano.yaci.core.types.UnitInterval ui) {
        if (ui == null) return BigDecimal.ZERO;
        if (ui.getDenominator() == null || ui.getDenominator().signum() == 0) return BigDecimal.ZERO;
        return new BigDecimal(ui.getNumerator()).divide(new BigDecimal(ui.getDenominator()), MathContext.DECIMAL128);
    }

    private static boolean hasAny(Object... values) {
        for (Object v : values) {
            if (v != null) return true;
        }
        return false;
    }
}
