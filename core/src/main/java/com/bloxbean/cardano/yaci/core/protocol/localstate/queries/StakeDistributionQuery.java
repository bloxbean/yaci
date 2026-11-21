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
public class StakeDistributionQuery implements EraQuery<StakeDistributionQueryResult> {
    private Era era;

    public StakeDistributionQuery() {
        this(Era.Conway);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        // GetStakeDistribution2 from N2C V21; GetStakeDistribution is unsupported there
        array.add(new UnsignedInteger(PoolDistributions.ledgerForm(protocolVersion) ? 37 : 5));

        return wrapWithOuterArray(array);
    }

    @Override
    public StakeDistributionQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        PoolDistributions.Distribution distribution =
                PoolDistributions.read(protocolVersion, extractResultArray(di[0]).get(0));
        return new StakeDistributionQueryResult(distribution.pools(), distribution.totalActiveStake());
    }
}
