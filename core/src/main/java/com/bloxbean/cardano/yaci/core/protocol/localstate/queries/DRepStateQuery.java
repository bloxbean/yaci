package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;


import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.DRepState;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Getter
@AllArgsConstructor
public class DRepStateQuery implements EraQuery<DRepStateQueryResult> {
    private Era era;
    private List<Credential> dRepCreds;

    public DRepStateQuery(List<Credential> dRepCreds) {
        this(Era.Conway, dRepCreds);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(25));

        Array dRepCredArray = new Array();

        dRepCreds.forEach(dRepCred -> dRepCredArray.add(dRepCred.serialize()));

        dRepCredArray.setTag(258);

        array.add(dRepCredArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public DRepStateQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        DRepStateQueryResult result = new DRepStateQueryResult();
        var array = (Array) di[0];
        var drepStateArray = (Array) array.getDataItems().get(1);

        drepStateArray.getDataItems().forEach(dataItem -> {
            var map = (Map)dataItem;
            var key = (List<DataItem>)map.getKeys();
            var itemDI = (Array)key.get(0);
            String dRepHash = CborSerializationUtil.toHex(itemDI.getDataItems().get(1));
            var value = (Array) map.get(itemDI);
            Integer expiry = CborSerializationUtil.toInt(value.getDataItems().get(0));
            // TODO: anchor
            BigInteger deposit = CborSerializationUtil.toBigInteger(value.getDataItems().get(2));
            var dRepState = DRepState.builder()
                    .dRepHash(dRepHash)
                    .deposit(deposit)
                    .expiry(expiry)
                    .build();

            result.addDRepState(dRepState);
        });

        return result;
    }
}
