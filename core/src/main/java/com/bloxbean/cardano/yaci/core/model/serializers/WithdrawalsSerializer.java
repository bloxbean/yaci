package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBytes;

public enum WithdrawalsSerializer implements Serializer<java.util.Map<String, BigInteger>> {
    INSTANCE;

    @Override
    public java.util.Map<String, BigInteger> deserializeDI(DataItem di) {
        Map map = (Map)di;

        java.util.Map<String, BigInteger> withdrawalsMap = new LinkedHashMap<>();

        Collection<DataItem> keys;
        keys = map.getKeys();
        for (DataItem key: keys) {
            String rewardAccount = HexUtil.encodeHexString(toBytes(key));
            BigInteger coin = toBigInteger(map.get(key));
            withdrawalsMap.put(rewardAccount, coin);
        }



        return Collections.unmodifiableMap(withdrawalsMap);
    }
}
