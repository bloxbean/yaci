package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

@Getter
@ToString
@AllArgsConstructor
public class DRepStakeDistributionQueryResult implements QueryResult {
    Map<Drep, BigInteger> dRepStakeMap;

    public DRepStakeDistributionQueryResult() {
        this.dRepStakeMap = new HashMap<>();
    }

    public void addDRepStake(Drep dRep, BigInteger amount) {
        dRepStakeMap.put(dRep, amount);
    }
}
