package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.math.BigDecimal;

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
    private BigDecimal motionNoConfidence;
    private BigDecimal committeeNormal;
    private BigDecimal committeeNoConfidence;
    private BigDecimal hardForkInitiation;
}
