package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@literal
 * treasury_withdrawals_action = (2, { reward_account => coin })
 * }
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class TreasuryWithdrawalsAction implements GovAction {
    private final GovActionType type = GovActionType.TREASURY_WITHDRAWALS_ACTION;

    @Builder.Default
    private Map<String, BigInteger> withdrawals = new LinkedHashMap<>();
}
