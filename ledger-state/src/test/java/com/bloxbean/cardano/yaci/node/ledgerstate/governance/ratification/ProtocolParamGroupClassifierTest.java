package com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.ratification.ProtocolParamGroupClassifier.ParamGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ProtocolParamGroupClassifier — parameter group classification
 * and threshold computation for governance voting.
 */
class ProtocolParamGroupClassifierTest {

    // ===== Group Classification =====

    @Test
    @DisplayName("committeeMinSize change → GOVERNANCE group only (no SECURITY)")
    void committeeMinSize_isGovernanceOnly() {
        // This is exactly what proposal 82 on preprod changed (committeeMinSize 7→3)
        var params = ProtocolParamUpdate.builder().committeeMinSize(3).build();
        var groups = ProtocolParamGroupClassifier.getAffectedGroups(params);
        assertThat(groups).containsExactly(ParamGroup.GOVERNANCE);
        assertThat(groups).doesNotContain(ParamGroup.SECURITY);
    }

    @Test
    @DisplayName("maxBlockSize change → NETWORK + SECURITY groups")
    void maxBlockSize_isNetworkAndSecurity() {
        var params = ProtocolParamUpdate.builder().maxBlockSize(90112).build();
        var groups = ProtocolParamGroupClassifier.getAffectedGroups(params);
        assertThat(groups).contains(ParamGroup.NETWORK, ParamGroup.SECURITY);
    }

    @Test
    @DisplayName("minFeeA change → ECONOMIC + SECURITY groups")
    void minFeeA_isEconomicAndSecurity() {
        var params = ProtocolParamUpdate.builder().minFeeA(44).build();
        var groups = ProtocolParamGroupClassifier.getAffectedGroups(params);
        assertThat(groups).contains(ParamGroup.ECONOMIC, ParamGroup.SECURITY);
    }

    @Test
    @DisplayName("nOpt change → TECHNICAL group only")
    void nOpt_isTechnicalOnly() {
        var params = ProtocolParamUpdate.builder().nOpt(500).build();
        var groups = ProtocolParamGroupClassifier.getAffectedGroups(params);
        assertThat(groups).containsExactly(ParamGroup.TECHNICAL);
    }

    @Test
    @DisplayName("Mixed change (minFeeA + committeeMinSize) → ECONOMIC + GOVERNANCE + SECURITY")
    void mixedChange_multipleGroups() {
        var params = ProtocolParamUpdate.builder()
                .minFeeA(44)
                .committeeMinSize(3)
                .build();
        var groups = ProtocolParamGroupClassifier.getAffectedGroups(params);
        assertThat(groups).contains(ParamGroup.ECONOMIC, ParamGroup.GOVERNANCE, ParamGroup.SECURITY);
    }

    @Test
    @DisplayName("Empty update → no groups affected")
    void emptyUpdate_noGroups() {
        var params = ProtocolParamUpdate.builder().build();
        var groups = ProtocolParamGroupClassifier.getAffectedGroups(params);
        assertThat(groups).isEmpty();
    }

    // ===== SPO Voting Required =====

    @Test
    @DisplayName("SECURITY group present → SPO voting required")
    void securityGroup_spoRequired() {
        assertThat(ProtocolParamGroupClassifier.isSpoVotingRequired(
                List.of(ParamGroup.NETWORK, ParamGroup.SECURITY))).isTrue();
    }

    @Test
    @DisplayName("GOVERNANCE only → SPO voting NOT required")
    void governanceOnly_spoNotRequired() {
        assertThat(ProtocolParamGroupClassifier.isSpoVotingRequired(
                List.of(ParamGroup.GOVERNANCE))).isFalse();
    }

    // ===== DRep Threshold Computation =====

    @Test
    @DisplayName("DRep threshold for GOVERNANCE group uses dvtPPGovGroup")
    void drepThreshold_governanceGroup() {
        var thresholds = com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds.builder()
                .dvtPPGovGroup(new UnitInterval(BigInteger.valueOf(3), BigInteger.valueOf(4)))       // 0.75
                .dvtPPNetworkGroup(new UnitInterval(BigInteger.valueOf(2), BigInteger.valueOf(3)))   // 0.667
                .build();
        var params = ProtocolParamUpdate.builder().drepVotingThresholds(thresholds).build();

        BigDecimal threshold = ProtocolParamGroupClassifier.computeDRepThreshold(
                List.of(ParamGroup.GOVERNANCE), params);
        assertThat(threshold).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("DRep threshold takes MAX across multiple groups")
    void drepThreshold_maxAcrossGroups() {
        var thresholds = com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds.builder()
                .dvtPPGovGroup(new UnitInterval(BigInteger.valueOf(3), BigInteger.valueOf(4)))          // 0.75
                .dvtPPNetworkGroup(new UnitInterval(BigInteger.valueOf(2), BigInteger.valueOf(3)))      // 0.667
                .dvtPPEconomicGroup(new UnitInterval(BigInteger.valueOf(51), BigInteger.valueOf(100)))  // 0.51
                .build();
        var params = ProtocolParamUpdate.builder().drepVotingThresholds(thresholds).build();

        BigDecimal threshold = ProtocolParamGroupClassifier.computeDRepThreshold(
                List.of(ParamGroup.GOVERNANCE, ParamGroup.NETWORK, ParamGroup.ECONOMIC), params);
        assertThat(threshold).isEqualByComparingTo("0.75"); // max of 0.75, 0.667, 0.51
    }

    @Test
    @DisplayName("SECURITY group contributes 0 to DRep threshold (DReps don't vote on security)")
    void drepThreshold_securityIsZero() {
        BigDecimal threshold = ProtocolParamGroupClassifier.computeDRepThreshold(
                List.of(ParamGroup.SECURITY),
                ProtocolParamUpdate.builder().build());
        assertThat(threshold).isEqualByComparingTo("0");
    }

    // ===== Utility =====

    @Test
    @DisplayName("ratioToBigDecimal handles null and zero denominator")
    void ratioToBigDecimal_edgeCases() {
        assertThat(ProtocolParamGroupClassifier.ratioToBigDecimal(null)).isEqualByComparingTo("0");
        assertThat(ProtocolParamGroupClassifier.ratioToBigDecimal(
                new UnitInterval(BigInteger.ONE, BigInteger.ZERO))).isEqualByComparingTo("0");
        assertThat(ProtocolParamGroupClassifier.ratioToBigDecimal(
                new UnitInterval(BigInteger.valueOf(2), BigInteger.valueOf(3))))
                .isEqualByComparingTo("0.6666666666666666666666666666666667");
    }
}
