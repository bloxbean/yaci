package com.bloxbean.cardano.yaci.core.model.governance;

import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import lombok.*;

import java.math.BigInteger;

/**
 * proposal_procedure =
 *   [ deposit : coin
 *   , reward_account
 *   , gov_action
 *   , anchor
 *   ]
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ProposalProcedure {
    private BigInteger deposit;
    private String rewardAccount;
    private GovAction govAction;
    private Anchor anchor;
}
