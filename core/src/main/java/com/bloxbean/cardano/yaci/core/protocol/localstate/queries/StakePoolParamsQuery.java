package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.Relay;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

import java.math.BigInteger;
import java.util.*;

import static com.bloxbean.cardano.yaci.core.model.serializers.PoolRegistrationSerializer.deserializeRelay;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

@Getter
@AllArgsConstructor
public class StakePoolParamsQuery implements EraQuery<StakePoolParamQueryResult> {
    private Era era;
    //Pool ids in hex
    private List<String> poolIds;

    public StakePoolParamsQuery(@NonNull List<String> poolIds) {
        this(Era.Conway, poolIds);
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
        List<DataItem> dataItemList = extractResultArray(di[0]);
        Map poolMap = (Map) dataItemList.get(0);

        java.util.Map<String, PoolParams> poolParamsMap = new LinkedHashMap<>();
        for (var key : poolMap.getKeys()) {
            String poolId = HexUtil.encodeHexString(((ByteString) key).getBytes());
            var poolParamDIList = ((Array)poolMap.get(key)).getDataItems();

            String operator = toHex(poolParamDIList.get(0));
            String vrfKeyHash = toHex(poolParamDIList.get(1));
            BigInteger pledge = toBigInteger(poolParamDIList.get(2));
            BigInteger cost = toBigInteger(poolParamDIList.get(3));
            UnitInterval margin = toUnitInterval(poolParamDIList.get(4));
            String rewardAccount = toHex(poolParamDIList.get(5));

            //Pool Owners0
            Set<String> poolOwners = new HashSet<>();
            List<DataItem> poolOwnersDataItems = ((Array) poolParamDIList.get(6)).getDataItems();
            for (DataItem poolOwnerDI : poolOwnersDataItems) {
                if (poolOwnerDI == SimpleValue.BREAK)
                    continue;
                poolOwners.add(toHex(poolOwnerDI));
            }

            //Relays
            List<DataItem> relaysDataItems = ((Array) poolParamDIList.get(7)).getDataItems();
            List<Relay> relays = new ArrayList<>();
            for (DataItem relayDI : relaysDataItems) {
                if (relayDI == SimpleValue.BREAK)
                    continue;
                relays.add(deserializeRelay(relayDI));
            }

            //pool metadata
            DataItem poolMetaDataDI = poolParamDIList.get(8);
            String metadataUrl = null;
            String metadataHash = null;
            if (poolMetaDataDI != SimpleValue.NULL) {
                List<DataItem> poolMetadataDataItems = ((Array) poolMetaDataDI).getDataItems();
                metadataUrl = toUnicodeString(poolMetadataDataItems.get(0));
                metadataHash = toHex(poolMetadataDataItems.get(1));
            }

            PoolParams poolParams = PoolParams.builder()
                    .operator(operator)
                    .vrfKeyHash(vrfKeyHash)
                    .pledge(pledge)
                    .cost(cost)
                    .margin(margin)
                    .rewardAccount(rewardAccount)
                    .poolOwners(poolOwners)
                    .relays(relays)
                    .poolMetadataUrl(metadataUrl)
                    .poolMetadataHash(metadataHash)
                    .build();

            poolParamsMap.put(poolId, poolParams);
        }

        return new StakePoolParamQueryResult(poolParamsMap);
    }
}
