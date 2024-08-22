package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.DRepStake;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter
@ToString
@AllArgsConstructor
public class DRepStakeDistributionQueryResult implements QueryResult {
    List<DRepStake> dRepStakes;

    public DRepStakeDistributionQueryResult() {
        this.dRepStakes = new ArrayList<>();
    }

    public void addDRepStake(DRepStake dRepState) {
        dRepStakes.add(dRepState);
    }
}
