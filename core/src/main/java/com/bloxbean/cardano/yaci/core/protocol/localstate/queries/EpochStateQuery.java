package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class EpochStateQuery implements EraQuery<EpochStateQueryResult> {
    private Era era;

    public EpochStateQuery() {
        this.era = Era.Babbage;
    }

    @Override
    public DataItem serialize() {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(8));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public EpochStateQueryResult deserializeResult(DataItem[] di) {
        System.out.println(HexUtil.encodeHexString(CborSerializationUtil.serialize(di)));
//        List<DataItem> dataItemList = extractResultArray(di[0]);
//        long epochNo = ((UnsignedInteger) dataItemList.get(0)).getValue().longValue();
//        return new EpochNoQueryResult(epochNo);
        return new EpochStateQueryResult();
    }

}
