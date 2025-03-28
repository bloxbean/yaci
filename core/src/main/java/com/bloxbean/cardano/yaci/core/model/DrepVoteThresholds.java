package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import lombok.*;

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
    private UnitInterval dvtMotionNoConfidence;
    private UnitInterval dvtCommitteeNormal;
    private UnitInterval dvtCommitteeNoConfidence;
    private UnitInterval dvtUpdateToConstitution;
    private UnitInterval dvtHardForkInitiation;
    private UnitInterval dvtPPNetworkGroup;
    private UnitInterval dvtPPEconomicGroup;
    private UnitInterval dvtPPTechnicalGroup;
    private UnitInterval dvtPPGovGroup;
    private UnitInterval dvtTreasuryWithdrawal;
}
