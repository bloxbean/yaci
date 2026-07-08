package com.bloxbean.cardano.yaci.core.protocol.appchainsync.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.ArrayList;
import java.util.List;

public class AppChainSyncSerializers {

    // Tag 0: MsgRequestRange [0, chainId(tstr), from(uint), to(uint)]
    public enum MsgRequestRangeSerializer implements Serializer<MsgRequestRange> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgRequestRange msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(new UnicodeString(msg.getChainId()));
            array.add(new UnsignedInteger(msg.getFromHeight()));
            array.add(new UnsignedInteger(msg.getToHeight()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgRequestRange deserializeDI(DataItem di) {
            List<DataItem> items = ((Array) di).getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 0)
                throw new CborRuntimeException("Invalid MsgRequestRange label: " + label);
            return new MsgRequestRange(
                    ((UnicodeString) items.get(1)).getString(),
                    ((UnsignedInteger) items.get(2)).getValue().longValue(),
                    ((UnsignedInteger) items.get(3)).getValue().longValue());
        }
    }

    // Tag 1: MsgBlocks [1, [blockCbor(bstr), ...], tip(uint)]
    public enum MsgBlocksSerializer implements Serializer<MsgBlocks> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgBlocks msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            Array blocksArr = new Array();
            blocksArr.setChunked(true);
            for (byte[] block : msg.getBlocks()) {
                blocksArr.add(new ByteString(block));
            }
            blocksArr.add(SimpleValue.BREAK);
            array.add(blocksArr);
            array.add(new UnsignedInteger(msg.getTipHeight()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgBlocks deserializeDI(DataItem di) {
            List<DataItem> items = ((Array) di).getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 1)
                throw new CborRuntimeException("Invalid MsgBlocks label: " + label);
            List<byte[]> blocks = new ArrayList<>();
            for (DataItem blockDI : ((Array) items.get(1)).getDataItems()) {
                if (blockDI instanceof Special) break;
                blocks.add(((ByteString) blockDI).getBytes());
            }
            long tip = ((UnsignedInteger) items.get(2)).getValue().longValue();
            return new MsgBlocks(blocks, tip);
        }
    }

    // Tag 2: MsgNoBlocks [2, tip(uint)]
    public enum MsgNoBlocksSerializer implements Serializer<MsgNoBlocks> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgNoBlocks msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));
            array.add(new UnsignedInteger(msg.getTipHeight()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgNoBlocks deserializeDI(DataItem di) {
            List<DataItem> items = ((Array) di).getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 2)
                throw new CborRuntimeException("Invalid MsgNoBlocks label: " + label);
            return new MsgNoBlocks(((UnsignedInteger) items.get(1)).getValue().longValue());
        }
    }

    // Tag 3: MsgDone [3]
    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgDone deserializeDI(DataItem di) {
            int label = ((UnsignedInteger) ((Array) di).getDataItems().get(0)).getValue().intValue();
            if (label != 3)
                throw new CborRuntimeException("Invalid MsgDone label: " + label);
            return new MsgDone();
        }
    }
}
