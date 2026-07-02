package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxsRequest;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class LeiosFetchSerializers {

    public enum MsgLeiosBlockRequestSerializer implements Serializer<MsgLeiosBlockRequest> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockRequest object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(LeiosCborUtil.serializePointArray(object.getPoint()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockRequest deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 0, "MsgLeiosBlockRequest");
            requireArity(items, 2, "MsgLeiosBlockRequest");
            return new MsgLeiosBlockRequest(LeiosCborUtil.deserializePointArray(items.get(1)));
        }
    }

    public enum MsgLeiosBlockSerializer implements Serializer<MsgLeiosBlock> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlock object) {
            return serializeArray(unsigned(1), LeiosCborUtil.validatedRawCborBytes(object.getEndorserBlock()));
        }

        @Override
        public MsgLeiosBlock deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 1, "MsgLeiosBlock");
            requireArity(items, 2, "MsgLeiosBlock");
            return new MsgLeiosBlock(LeiosCborUtil.toRawCbor(items.get(1)));
        }
    }

    public enum MsgLeiosBlockTxsRequestSerializer implements Serializer<MsgLeiosBlockTxsRequest> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockTxsRequest object) {
            return serializeArray(
                    unsigned(2),
                    CborSerializationUtil.serialize(LeiosCborUtil.serializePointArray(object.getPoint()), false),
                    LeiosCborUtil.serializeTxBitmapBytes(object.getBitmap()));
        }

        @Override
        public MsgLeiosBlockTxsRequest deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 2, "MsgLeiosBlockTxsRequest");
            requireArity(items, 3, "MsgLeiosBlockTxsRequest");
            LeiosPoint point = LeiosCborUtil.deserializePointArray(items.get(1));
            LeiosTxBitmap bitmap = LeiosCborUtil.deserializeTxBitmap(items.get(2));
            return new MsgLeiosBlockTxsRequest(point, bitmap);
        }
    }

    public enum MsgLeiosBlockTxsSerializer implements Serializer<MsgLeiosBlockTxs> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockTxs object) {
            return serializeArray(
                    unsigned(3),
                    CborSerializationUtil.serialize(LeiosCborUtil.serializePointArray(object.getPoint()), false),
                    LeiosCborUtil.serializeTxBitmapBytes(object.getBitmap()),
                    LeiosCborUtil.validatedRawCborBytes(object.getTxList()));
        }

        @Override
        public MsgLeiosBlockTxs deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 3, "MsgLeiosBlockTxs");
            requireArity(items, 4, "MsgLeiosBlockTxs");
            LeiosPoint point = LeiosCborUtil.deserializePointArray(items.get(1));
            LeiosTxBitmap bitmap = LeiosCborUtil.deserializeTxBitmap(items.get(2));
            return new MsgLeiosBlockTxs(point, bitmap, LeiosCborUtil.toRawCbor(items.get(3)));
        }
    }

    public enum MsgClientDoneSerializer implements Serializer<MsgClientDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgClientDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(9));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgClientDone deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 9, "MsgClientDone");
            requireArity(items, 1, "MsgClientDone");
            return new MsgClientDone();
        }
    }

    private static List<DataItem> messageItems(DataItem di, int expectedTag, String messageName) {
        int tag = LeiosCborUtil.readTag(di);
        if (tag != expectedTag) {
            throw new IllegalArgumentException(messageName + " expected tag " + expectedTag + " but found " + tag);
        }
        return LeiosCborUtil.arrayItems(di, messageName);
    }

    private static void requireArity(List<DataItem> items, int expected, String messageName) {
        if (items.size() != expected) {
            throw new IllegalArgumentException(messageName + " must have " + expected + " fields");
        }
    }

    private static byte[] serializeArray(byte[]... encodedItems) {
        if (encodedItems.length > 23) {
            throw new IllegalArgumentException("small CBOR array supports at most 23 items");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x80 | encodedItems.length);
        for (byte[] encodedItem : encodedItems) {
            baos.write(encodedItem, 0, encodedItem.length);
        }
        return baos.toByteArray();
    }

    private static byte[] unsigned(long value) {
        return CborSerializationUtil.serialize(new UnsignedInteger(value), false);
    }
}
