package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class PoolDistrQuery implements EraQuery<PoolDistrQueryResult> {
    private Era era;
    /** Pool ids in hex; null for every pool. */
    private List<String> poolIds;

    public PoolDistrQuery(List<String> poolIds) {
        this(Era.Conway, poolIds);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        // GetPoolDistr2 from N2C V21; GetPoolDistr is unsupported there
        array.add(new UnsignedInteger(PoolDistributions.ledgerForm(protocolVersion) ? 36 : 21));

        // Maybe (Set PoolId): [] for every pool, [set] for the pools given (cardano-binary encodeMaybe)
        Array maybePoolIds = new Array();
        if (poolIds != null) {
            maybePoolIds.add(QuerySets.poolIds(poolIds));
        }
        array.add(maybePoolIds);

        return wrapWithOuterArray(array);
    }

    @Override
    public PoolDistrQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        PoolDistributions.Distribution distribution =
                PoolDistributions.read(protocolVersion, extractResultArray(di[0]).get(0));
        return new PoolDistrQueryResult(distribution.pools(), distribution.totalActiveStake());
    }
}
