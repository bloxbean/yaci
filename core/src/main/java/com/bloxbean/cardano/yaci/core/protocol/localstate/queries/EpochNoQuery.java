package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@AllArgsConstructor
@ToString
public class EpochNoQuery implements EraQuery<EpochNoQueryResult> {
    private Era era;

    public EpochNoQuery() {
        this.era = Era.Babbage;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(1));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public EpochNoQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        List<DataItem> dataItemList = extractResultArray(di[0]);
        long epochNo = ((UnsignedInteger) dataItemList.get(0)).getValue().longValue();
        return new EpochNoQueryResult(epochNo);
    }

}
