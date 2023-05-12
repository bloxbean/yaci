package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

import java.util.List;

@Getter
@AllArgsConstructor
public class StakePoolParamsQuery implements EraQuery<StakePoolParamQueryResult> {
    private Era era;
    //Pool ids in hex
    private List<String> poolIds;

    public StakePoolParamsQuery(@NonNull List<String> poolIds) {
        this(Era.Babbage, poolIds);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(17));

        Array poolIdArray = new Array();
        poolIds.forEach(poolId -> poolIdArray.add(new ByteString(HexUtil.decodeHexString(poolId))));
        poolIdArray.setTag(258);

        array.add(poolIdArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public StakePoolParamQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
