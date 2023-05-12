package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

@Getter
@AllArgsConstructor
public class StakeDistributionQuery implements EraQuery<StakeDistributionQueryResult> {
    private Era era;

    public StakeDistributionQuery() {
        this(Era.Babbage);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(5));

        return wrapWithOuterArray(array);
    }

    @Override
    public StakeDistributionQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        //A map of stake pool IDs (the hash of the stake pool operator's
        //verification key) to 'IndividualPoolStake'.
        List<DataItem> dataItems = extractResultArray(di[0]);
        Map poolDistrMap = (Map) dataItems.get(0);

        java.util.Map<String, IndividualPoolStake> poolDistributionMap = new LinkedHashMap<>();
        for (DataItem key : poolDistrMap.getKeys()) {
            String stakePoolKeyHash = toHex(key);
            Array valueArr = (Array) poolDistrMap.get(key);
            List<DataItem> stakeDIs = ((Array) valueArr.getDataItems().get(0)).getDataItems();
            DataItem stakeVrfDI = valueArr.getDataItems().get(1);

            BigInteger stakeNumerator = toBigInteger(stakeDIs.get(0));
            BigInteger stakeDenominator = toBigInteger(stakeDIs.get(1));
            String vrfKeyHash = toHex(stakeVrfDI);

            poolDistributionMap.put(vrfKeyHash, new IndividualPoolStake(stakePoolKeyHash, stakeNumerator, stakeDenominator, vrfKeyHash));
        }

        return new StakeDistributionQueryResult(poolDistributionMap);
    }
}
