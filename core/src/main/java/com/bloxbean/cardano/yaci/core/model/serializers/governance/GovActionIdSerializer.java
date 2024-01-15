package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public enum GovActionIdSerializer implements Serializer<GovActionId> {
    INSTANCE;

    @Override
    public GovActionId deserializeDI(DataItem di) {
        Array actionIdArray = (Array) di;
        if (actionIdArray != null && actionIdArray.getDataItems().size() != 2)
            throw new IllegalArgumentException("Invalid gov_action_id array. Expected 2 items. Found : "
                    + actionIdArray.getDataItems().size());

        List<DataItem> diList = actionIdArray.getDataItems();
        String txId = HexUtil.encodeHexString(((ByteString) diList.get(0)).getBytes());
        int govActionIndex = ((UnsignedInteger) diList.get(1)).getValue().intValue();

        return new GovActionId(txId, govActionIndex);
    }
}
