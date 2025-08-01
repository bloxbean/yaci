package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronEbBlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.RollForward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

public enum RollForwardSerializer implements Serializer<RollForward> {
    INSTANCE;

    public RollForward deserialize(byte[] bytes) {
        Array headerContentArr = (Array)CborSerializationUtil.deserializeOne(bytes); //rollforward
        List<DataItem> headerContentDI = headerContentArr.getDataItems();
        int rollForwardType = toInt(headerContentDI.get(0));

        Array wrappedHeader = (Array) headerContentDI.get(1);

        int eraVariant = toInt(wrappedHeader.getDataItems().get(0));
        ByronEbHead byronEbHead = null;
        ByronBlockHead byronBlockHead = null;
        BlockHeader blockHeader = null;
        //Check if Byron header. No documentation found for this.
        if (eraVariant == 0) {
            Array byronPrefixAndHeaderDI = (Array)(wrappedHeader.getDataItems().get(1));
            Array byronPrefixArr = ((Array)byronPrefixAndHeaderDI.getDataItems().get(0));
            int byronPrefix_1 = toInt(byronPrefixArr.getDataItems().get(0));
            int byronPrefix_2 = toInt(byronPrefixArr.getDataItems().get(1));

            if (byronPrefix_1 == 0) {
                byte[] headerBytes = ((ByteString) (byronPrefixAndHeaderDI.getDataItems().get(1))).getBytes();
                DataItem headerDI = CborSerializationUtil.deserializeOne(headerBytes);
                byronEbHead = ByronEbBlockSerializer.INSTANCE.deserializeHeader((Array) headerDI);
            } else if (byronPrefix_1 == 1) {
                byte[] headerBytes = ((ByteString) (byronPrefixAndHeaderDI.getDataItems().get(1))).getBytes();
                DataItem headerDI = CborSerializationUtil.deserializeOne(headerBytes);
                byronBlockHead = ByronBlockSerializer.INSTANCE.deserializeHeader((Array) headerDI);
            }
        } else { //Shelley and later versions
            blockHeader =
                    BlockHeaderSerializer.INSTANCE.deserializeDI(wrappedHeader.getDataItems().get(1));
        }

        // Extract complete wrapped header bytes for storage (includes era variant)
        byte[] originalHeaderBytes = null;
        try {
            // Store the complete wrappedHeader structure with era variant
            originalHeaderBytes = CborSerializationUtil.serialize(wrappedHeader, false);
        } catch (Exception e) {
            // If extraction fails, continue without original bytes
            originalHeaderBytes = null;
        }

        Tip tip = TipSerializer.INSTANCE.deserializeDI(headerContentDI.get(2));
        return new RollForward(byronEbHead, byronBlockHead, blockHeader, tip, originalHeaderBytes);
    }

    @Override
    public DataItem serializeDI(RollForward object) {
        Array rollForwardArray = new Array();

        // Add rollForwardType (2 for RollForward)
        rollForwardArray.add(new UnsignedInteger(2));

        // Create wrapped header - use stored complete wrapped header if available
        Array wrappedHeader;

        if (object.getOriginalHeaderBytes() != null && object.getOriginalHeaderBytes().length > 0) {
            // Use stored complete wrapped header (includes era variant)
            wrappedHeader = (Array) CborSerializationUtil.deserializeOne(object.getOriginalHeaderBytes());
        } else {
            // Fall back to reconstructing wrapped header (legacy support)
            wrappedHeader = new Array();

            if (object.getByronEbHead() != null) {
                // Byron Epoch Boundary block
                wrappedHeader.add(new UnsignedInteger(0)); // Byron era variant

                Array byronPrefixAndHeader = new Array();
                Array byronPrefix = new Array();
                byronPrefix.add(new UnsignedInteger(0)); // EB block prefix
                byronPrefix.add(new UnsignedInteger(0)); // Additional prefix
                byronPrefixAndHeader.add(byronPrefix);

                // Serialize Byron EB header
                // TODO: Implement serializeDI for Byron serializers
                // For now, we'll throw an exception to indicate this needs implementation
                throw new UnsupportedOperationException("Byron EB block serialization not yet implemented");

            } else if (object.getByronBlockHead() != null) {
                // Byron Main block
                wrappedHeader.add(new UnsignedInteger(0)); // Byron era variant

                Array byronPrefixAndHeader = new Array();
                Array byronPrefix = new Array();
                byronPrefix.add(new UnsignedInteger(1)); // Main block prefix
                byronPrefix.add(new UnsignedInteger(0)); // Additional prefix
                byronPrefixAndHeader.add(byronPrefix);

                // Serialize Byron Main header
                // TODO: Implement serializeDI for Byron serializers
                // For now, we'll throw an exception to indicate this needs implementation
                throw new UnsupportedOperationException("Byron Main block serialization not yet implemented");

            } else if (object.getBlockHeader() != null) {
                // Shelley and later eras - fallback requires era detection
                // Note: This path should rarely be used with new implementation
                wrappedHeader.add(new UnsignedInteger(1)); // Default to Shelley for fallback

                // Fall back to serializer (may fail if not implemented)
                wrappedHeader.add(BlockHeaderSerializer.INSTANCE.serializeDI(object.getBlockHeader()));
            }
        }

        rollForwardArray.add(wrappedHeader);

        // Add Tip
        rollForwardArray.add(TipSerializer.INSTANCE.serializeDI(object.getTip()));

        return rollForwardArray;
    }
}
