package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import lombok.*;

/**
 * pool_voting_thresholds =
 *   [ unit_interval ; motion no confidence
 *   , unit_interval ; committee normal
 *   , unit_interval ; committee no confidence
 *   , unit_interval ; hard fork initiation
 *   ]
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class PoolVotingThresholds {
    private UnitInterval pvtMotionNoConfidence;
    private UnitInterval pvtCommitteeNormal;
    private UnitInterval pvtCommitteeNoConfidence;
    private UnitInterval pvtHardForkInitiation;
    private UnitInterval pvtPPSecurityGroup;
}
