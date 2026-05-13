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
        byte[] blockBytes = blockContent.getBytes();
        Array blockArray = (Array) CborSerializationUtil.deserializeOne(blockBytes);

        int eraValue = ((UnsignedInteger)blockArray.getDataItems().get(0)).getValue().intValue();
        Era era = EraUtil.getEra(eraValue);

        Block block = null;
        ByronEbBlock byronEbBlock = null;
        ByronMainBlock byronMainBlock = null;
        if (era == Era.Byron) {
            if (eraValue == 0) {
                byronEbBlock = ByronEbBlockSerializer.INSTANCE.deserialize(blockBytes);
            } else {
                byronMainBlock = ByronBlockSerializer.INSTANCE.deserialize(blockBytes);
            }
        } else {
            block = BlockSerializer.INSTANCE.deserialize(blockBytes);
        }

        Tip tip = TipSerializer.INSTANCE.deserializeDI(contentDI.get(2));
        return new LocalRollForward(byronEbBlock, byronMainBlock, block, tip);
    }

    //TODO -- Need tests
    @Override
    public DataItem serializeDI(LocalRollForward object) {
        Array contentArr = new Array();

        // Add the rollForwardType for serialization
        if (object.getByronEbBlock() != null) {
            contentArr.add(new UnsignedInteger(0)); // Era 0 for ByronEbBlock
        } else if (object.getByronBlock() != null) {
            contentArr.add(new UnsignedInteger(1)); // ByronMainBlock era value TODO ??
        } else if (object.getBlock() != null) {
            contentArr.add(new UnsignedInteger(object.getBlock().getEra().getValue())); // Other block's era value
        } else {
            throw new IllegalArgumentException("Incomplete LocalRollForward object.");
        }

        // Add the serialized block
        byte[] blockBytes;
        if (object.getByronEbBlock() != null) {
            blockBytes = ByronEbBlockSerializer.INSTANCE.serialize(object.getByronEbBlock());
        } else if (object.getByronBlock() != null) {
            blockBytes = ByronBlockSerializer.INSTANCE.serialize(object.getByronBlock());
        } else {
            blockBytes = BlockSerializer.INSTANCE.serialize(object.getBlock());
        }
        contentArr.add(new ByteString(blockBytes));

        // Add the serialized Tip
        contentArr.add(TipSerializer.INSTANCE.serializeDI(object.getTip()));

        return contentArr;
    }
}
