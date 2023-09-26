package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

/**
 * drep =
 *   [ 0, addr_keyhash
 *   // 1, scripthash
 *   // 2  ; always abstain
 *   // 3  ; always no confidence
 *   ]
 */
public enum DrepSerializer implements Serializer<Drep> {
    INSTANCE;

    @Override
    public Drep deserializeDI(DataItem di) {
        List<DataItem> dataItemList = ((Array) di).getDataItems();

        int key = toInt(dataItemList.get(0));

        switch (key) {
            case 1:
                String addKeyHash = toHex(dataItemList.get(1));
                return Drep.addrKeyHash(addKeyHash);
            case 2:
                String scriptHash = toHex(dataItemList.get(1));
                return Drep.scriptHash(scriptHash);
            case 3:
                return Drep.abstain();
            case 4:
                return Drep.noConfidence();
            default:
                throw new IllegalArgumentException("Invalid Drep key: " + key);
        }
    }
}
