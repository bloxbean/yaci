package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
//TODO -- Not implemented yet
public class GenesisConfigQuery implements EraQuery<GenesisConfigQueryResult> {
    private Era era;

    @Override
    public DataItem serialize() {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(11));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public GenesisConfigQueryResult deserializeResult(DataItem[] di) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

}
