package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class PoolDistrQuery implements EraQuery<PoolDistrQueryResult> {
    private Era era;
    //Pool id in hex
    private List<String> poolIds;

    public PoolDistrQuery(List<String> poolIds) {
        this(Era.Babbage, poolIds);
    }

    @Override
    public DataItem serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(21));

        Array poolIdArray = new Array();
        poolIds.forEach(poolId -> poolIdArray.add(new ByteString(HexUtil.decodeHexString(poolId))));
        poolIdArray.setTag(258);

        array.add(poolIdArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public PoolDistrQueryResult deserializeResult(DataItem[] di) {
        System.out.println(HexUtil.encodeHexString(CborSerializationUtil.serialize(di)));
        return new PoolDistrQueryResult();
    }
}
