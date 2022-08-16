package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.RollForward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum RollForwardSerializer implements Serializer<RollForward> {
    INSTANCE;

    public RollForward deserialize(byte[] bytes) {
        Array array = (Array)CborSerializationUtil.deserialize(bytes);

       Array wrappedHeader = (Array) array.getDataItems().get(1);
       BlockHeader blockHeader =
               BlockHeaderSerializer.INSTANCE.deserializeDI(wrappedHeader.getDataItems().get(1));

       Tip tip = TipSerializer.INSTANCE.deserializeDI(array.getDataItems().get(2));

       return new RollForward(blockHeader, tip);
    }

}
