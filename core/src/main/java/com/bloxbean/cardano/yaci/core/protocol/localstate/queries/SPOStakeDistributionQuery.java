package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Getter
@ToString
@AllArgsConstructor
@Slf4j
public class SPOStakeDistributionQuery implements EraQuery<SPOStakeDistributionQueryResult> {
    private Era era;
    private List<String> poolIds;

    public SPOStakeDistributionQuery(List<String> poolIds) {
        this(Era.Conway, poolIds);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(30));

        Array poolIdArray = new Array();
        poolIds.forEach(poolId -> poolIdArray.add(new ByteString(HexUtil.decodeHexString(poolId))));

        array.add(poolIdArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public SPOStakeDistributionQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        SPOStakeDistributionQueryResult result = new SPOStakeDistributionQueryResult();

        List<DataItem> dataItemList = extractResultArray(di[0]);
        Map spoStakeMap = (Map) dataItemList.get(0);

        for (var key : spoStakeMap.getKeys()) {
            StakePoolId stakePoolId = new StakePoolId(((ByteString) key).getBytes());
            var amount = ((UnsignedInteger) spoStakeMap.get(key)).getValue();

            result.addStakePoolStake(stakePoolId, amount);
        }

        return result;
    }
}
