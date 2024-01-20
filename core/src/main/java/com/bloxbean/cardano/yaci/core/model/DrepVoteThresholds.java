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
    private BigDecimal dvtMotionNoConfidence;
    private BigDecimal dvtCommitteeNormal;
    private BigDecimal dvtCommitteeNoConfidence;
    private BigDecimal dvtUpdateToConstitution;
    private BigDecimal dvtHardForkInitiation;
    private BigDecimal dvtPPNetworkGroup;
    private BigDecimal dvtPPEconomicGroup;
    private BigDecimal dvtPPTechnicalGroup;
    private BigDecimal dvtPPGovGroup;
    private BigDecimal dvtTreasuryWithdrawal;
}
