package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Getter
@ToString
@AllArgsConstructor
@Slf4j
public class DRepStakeDistributionQuery implements EraQuery<DRepStakeDistributionResult> {
    private Era era;
    private List<DRep> dReps;

    public DRepStakeDistributionQuery(List<DRep> dReps) {
        this(Era.Conway, dReps);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(26));

        Array dRepArray = new Array();
        dReps.forEach(dRep -> dRepArray.add(dRep.serialize()));

        array.add(dRepArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public DRepStakeDistributionResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
