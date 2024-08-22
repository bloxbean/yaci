package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.math.BigInteger;
import java.util.List;

@Getter
@AllArgsConstructor
@ToString
public class AccountStateQuery implements EraQuery<AccountStateQueryResult> {
    @NonNull
    private Era era;

    public AccountStateQuery() {
        this.era = Era.Conway;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(29));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public AccountStateQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        List<DataItem> dataItems = extractResultArray(di[0]);

        var treasuryDI = ((Array)dataItems.get(0)).getDataItems().get(0);
        var reservesDI = ((Array)dataItems.get(0)).getDataItems().get(1);

        BigInteger treasury = ((UnsignedInteger)treasuryDI).getValue();
        BigInteger reserves = ((UnsignedInteger)reservesDI).getValue();

        return new AccountStateQueryResult(treasury, reserves);
    }
}
