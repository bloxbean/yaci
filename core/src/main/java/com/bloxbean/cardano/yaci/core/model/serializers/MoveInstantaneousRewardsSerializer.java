package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

public enum MoveInstantaneousRewardsSerializer implements Serializer<MoveInstataneous> {
    INSTANCE;

    @Override
    public MoveInstataneous deserializeDI(DataItem di) {
        Array moveInstaDIArr = (Array) di;
        List<DataItem> dataItemList = moveInstaDIArr.getDataItems();
        if (dataItemList == null || dataItemList.size() != 2) {
            throw new CborRuntimeException("MoveInstantaneous Rewards deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
        if (type == null || type.getValue().intValue() != 6)
            throw new CborRuntimeException("MoveInstantaneous Rewards deserialization failed. Invalid type : "
                    + type != null ? String.valueOf(type.getValue().intValue()) : null);

        List<DataItem> moveInstDIList = ((Array)dataItemList.get(1)).getDataItems();

        boolean treasury = false;
        boolean reserves = false;
        int fundsDrawnFrom = toInt(moveInstDIList.get(0));
        if (fundsDrawnFrom == 0)
            reserves = true;
        if (fundsDrawnFrom == 1)
            treasury = true;

        java.util.Map stakeCredentialsMap = new LinkedHashMap();
        BigInteger accountingPotCoin = null;
        DataItem fundsMoveDI = moveInstDIList.get(1);
        if (fundsMoveDI.getMajorType() == MajorType.MAP) { //funds are moved to stake credentials
            Map fundsMoveDIMap = (Map) fundsMoveDI;

            Collection<DataItem> keys = fundsMoveDIMap.getKeys();
            for (DataItem key: keys) {
                DataItem deltaCoinDI = fundsMoveDIMap.get(key);
                BigInteger deltaCoinValue = toBigInteger(deltaCoinDI);
                StakeCredential stakeCredential = StakeCredentialSerializer.INSTANCE.deserializeDI(key);
                stakeCredentialsMap.put(stakeCredential, deltaCoinValue);
            }
        } else { //funds are given to the other accounting pot
               accountingPotCoin = toBigInteger(fundsMoveDI);
        }

        return new MoveInstataneous(reserves, treasury, accountingPotCoin, stakeCredentialsMap);
    }
}
