package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StakeSnapshotQuery implements EraQuery<StakeSnapshotQueryResult> {
    private Era era;
    //Pool id in hex
    private String poolId;

    public StakeSnapshotQuery(String poolId) {
        this(Era.Babbage, poolId);
    }

    @Override
    public DataItem serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(20));

        Array poolIdArray = new Array();
        poolIdArray.add(new ByteString(HexUtil.decodeHexString(poolId)));
        poolIdArray.setTag(258);

        array.add(poolIdArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public StakeSnapshotQueryResult deserializeResult(DataItem[] di) {
       throw new UnsupportedOperationException("Not implemented yet");
    }
}
