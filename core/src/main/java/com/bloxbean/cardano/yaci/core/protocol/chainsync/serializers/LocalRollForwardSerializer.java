package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronEbBlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.LocalRollForward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

public enum LocalRollForwardSerializer implements Serializer<LocalRollForward> {
    INSTANCE;

    public LocalRollForward deserialize(byte[] bytes) {
        Array contentArr = (Array)CborSerializationUtil.deserializeOne(bytes);
        List<DataItem> contentDI = contentArr.getDataItems();
        int rollForwardType = toInt(contentDI.get(0));

        ByteString blockContent = (ByteString) contentDI.get(1);
        Array blockArray = (Array) CborSerializationUtil.deserializeOne(blockContent.getBytes());

        int eraValue = ((UnsignedInteger)blockArray.getDataItems().get(0)).getValue().intValue();
        Era era = EraUtil.getEra(eraValue);

        Block block = null;
        ByronEbBlock byronEbBlock = null;
        ByronMainBlock byronMainBlock = null;
        if (era == Era.Byron) {
            if (eraValue == 0) {
                byronEbBlock = ByronEbBlockSerializer.INSTANCE.deserializeDI(blockArray);
            } else {
                byronMainBlock = ByronBlockSerializer.INSTANCE.deserializeDI(blockArray);
            }
        } else {
            block = BlockSerializer.INSTANCE.deserializeDI(blockArray);
        }

        Tip tip = TipSerializer.INSTANCE.deserializeDI(contentDI.get(2));
        return new LocalRollForward(byronEbBlock, byronMainBlock, block, tip);
    }

}
