package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.DRepState;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@ToString
@AllArgsConstructor
public class DRepStateQueryResult implements QueryResult {
    List<DRepState> dRepStates;

    public DRepStateQueryResult() {
        this.dRepStates = new ArrayList<>();
    }

    public void addDRepState(DRepState dRepState) {
        dRepStates.add(dRepState);
    }
}
