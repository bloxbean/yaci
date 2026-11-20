package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.PoolStakeSnapshot;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

@Getter
@AllArgsConstructor
public class StakeSnapshotQuery implements EraQuery<StakeSnapshotQueryResult> {
    private Era era;
    //Pool id in hex
    private String poolId;

    public StakeSnapshotQuery(String poolId) {
        this(Era.Conway, poolId);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(20));

        // Maybe (Set PoolId) (cardano-binary encodeMaybe): [] for every pool, [set] for the pool given
        Array maybePoolIds = new Array();
        if (poolId != null)
            maybePoolIds.add(QuerySets.poolIds(List.of(poolId)));

        array.add(maybePoolIds);

        return wrapWithOuterArray(array);
    }

    @Override
    public StakeSnapshotQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        // [{pool => [mark, set, go]}, mark total, set total, go total] (cardano-ledger StakeSnapshots)
        List<DataItem> snapshots = ((Array) extractResultArray(di[0]).get(0)).getDataItems();
        Map pools = (Map) snapshots.get(0);
        java.util.Map<String, PoolStakeSnapshot> poolStakeSnapshots = new LinkedHashMap<>();
        for (DataItem key : pools.getKeys()) {
            List<DataItem> stake = ((Array) pools.get(key)).getDataItems();
            poolStakeSnapshots.put(toHex(key),
                    new PoolStakeSnapshot(toBigInteger(stake.get(0)), toBigInteger(stake.get(1)), toBigInteger(stake.get(2))));
        }
        return new StakeSnapshotQueryResult(poolStakeSnapshots, toBigInteger(snapshots.get(1)),
                toBigInteger(snapshots.get(2)), toBigInteger(snapshots.get(3)));
    }
}
