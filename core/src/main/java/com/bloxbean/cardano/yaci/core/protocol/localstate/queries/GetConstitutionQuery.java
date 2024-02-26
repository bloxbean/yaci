package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GetConstitutionQuery implements EraQuery<GetConstitutionResult> {
    private Era era;

    public GetConstitutionQuery() {
        this(Era.Conway);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(23));

        return wrapWithOuterArray(array);
    }

    @Override
    public GetConstitutionResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        System.out.println(di[0]);
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
