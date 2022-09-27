package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.RollForward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

public enum RollForwardSerializer implements Serializer<RollForward> {
    INSTANCE;

    public RollForward deserialize(byte[] bytes) {
        Array headerContentArr = (Array)CborSerializationUtil.deserializeOne(bytes);
        List<DataItem> headerContentDI = headerContentArr.getDataItems();
        int rollForwardType = toInt(headerContentDI.get(0));

        Array wrappedHeader = (Array) headerContentDI.get(1);

        int eraVariant = toInt(wrappedHeader.getDataItems().get(0));
        ByronBlockHead byronBlockHead = null;
        BlockHeader blockHeader = null;
        //Check if Byron header. No documentation found for this.
        if (eraVariant == 0) {
            Array byronPrefixAndHeaderDI = (Array)(wrappedHeader.getDataItems().get(1));
            Array byronPrefixArr = ((Array)byronPrefixAndHeaderDI.getDataItems().get(0));
            int byronPrefix_1 = toInt(byronPrefixArr.getDataItems().get(0));
            int byronPrefix_2 = toInt(byronPrefixArr.getDataItems().get(1));

            byte[] headerBytes =((ByteString)(byronPrefixAndHeaderDI.getDataItems().get(1))).getBytes();
            DataItem headerDI = CborSerializationUtil.deserializeOne(headerBytes);
            byronBlockHead = ByronBlockSerializer.INSTANCE.deserializeHeader((Array) headerDI);
        } else { //Shelley and later versions
            blockHeader =
                    BlockHeaderSerializer.INSTANCE.deserializeDI(wrappedHeader.getDataItems().get(1));
        }

        Tip tip = TipSerializer.INSTANCE.deserializeDI(headerContentDI.get(2));
        return new RollForward(byronBlockHead, blockHeader, tip);
    }

}
