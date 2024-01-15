package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;

/**
 * gov_action =
 *   [ parameter_change_action
 *   // hard_fork_initiation_action
 *   // treasury_withdrawals_action
 *   // no_confidence
 *   // update_committee
 *   // new_constitution
 *   // info_action
 *   ]
 */
public interface GovAction {
    GovActionType getType();
}
