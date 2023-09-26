package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.math.BigDecimal;

/**
 * drep_voting_thresholds =
 *   [ unit_interval ; motion no confidence
 *   , unit_interval ; committee normal
 *   , unit_interval ; committee no confidence
 *   , unit_interval ; update constitution
 *   , unit_interval ; hard fork initiation
 *   , unit_interval ; PP network group
 *   , unit_interval ; PP economic group
 *   , unit_interval ; PP technical group
 *   , unit_interval ; PP governance group
 *   , unit_interval ; treasury withdrawal
 *   ]
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class DrepVoteThresholds {
    private BigDecimal motionNoConfidence;
    private BigDecimal committeeNormal;
    private BigDecimal committeeNoConfidence;
    private BigDecimal updateConstitution;
    private BigDecimal hardForkInitiation;
    private BigDecimal ppNetworkGroup;
    private BigDecimal ppEconomicGroup;
    private BigDecimal ppTechnicalGroup;
    private BigDecimal ppGovernanceGroup;
    private BigDecimal treasuryWithdrawal;
}
