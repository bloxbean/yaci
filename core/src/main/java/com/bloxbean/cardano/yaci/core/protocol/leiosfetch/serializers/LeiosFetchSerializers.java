package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;

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

/**
 * LeiosFetch (mini-protocol 19) codecs of Musashi rc-2026w42: {@code LeiosDemoOnlyTestFetch.hs} at ouroboros-consensus
 * 840e789b, matching cardano-blueprint {@code main} @ 0460c5a6:
 * Done=0, BlockRequest=1, Block=2, BlockTxsRequest=3, BlockTxs=4.
 * {@code MsgLeiosBlockTxs} carries only the transactions; the reply is matched to the outstanding request.
 */
public class LeiosFetchSerializers {

    public enum MsgLeiosBlockRequestSerializer implements Serializer<MsgLeiosBlockRequest> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockRequest object) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            array.add(LeiosCborUtil.serializePointArray(object.getPoint()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockRequest deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 1, "MsgLeiosBlockRequest");
            requireArity(items, 2, "MsgLeiosBlockRequest");
            return new MsgLeiosBlockRequest(LeiosCborUtil.deserializePointArray(items.get(1)));
        }
    }

    public enum MsgLeiosBlockSerializer implements Serializer<MsgLeiosBlock> {
        INSTANCE;

        /** Decodes from the bytes as received: the payload is sliced, never re-encoded (ADR 0016 D2). */
        @Override
        public MsgLeiosBlock deserialize(byte[] bytes) {
            Message message = LeiosFetchStateBase.deserialize(bytes);
            if (message instanceof MsgLeiosBlock decoded)
                return decoded;
            throw notA("MsgLeiosBlock", message);
        }

        @Override
        public byte[] serialize(MsgLeiosBlock object) {
            return serializeArray(unsigned(2), LeiosCborUtil.validatedRawCborBytes(object.getWirePayload()));
        }

        /** Not supported: a decoded item has lost the payload's bytes as received. */
        @Override
        public MsgLeiosBlock deserializeDI(DataItem di) {
            throw new UnsupportedOperationException("use deserialize(byte[]): the payload must keep its bytes");
        }
    }

    public enum MsgLeiosBlockTxsRequestSerializer implements Serializer<MsgLeiosBlockTxsRequest> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockTxsRequest object) {
            return serializeArray(
                    unsigned(3),
                    CborSerializationUtil.serialize(LeiosCborUtil.serializePointArray(object.getPoint()), false),
                    LeiosCborUtil.serializeTxBitmapBytes(object.getBitmap()));
        }

        @Override
        public MsgLeiosBlockTxsRequest deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 3, "MsgLeiosBlockTxsRequest");
            requireArity(items, 3, "MsgLeiosBlockTxsRequest");
            LeiosPoint point = LeiosCborUtil.deserializePointArray(items.get(1));
            LeiosTxBitmap bitmap = LeiosCborUtil.deserializeTxBitmap(items.get(2));
            return new MsgLeiosBlockTxsRequest(point, bitmap);
        }
    }

    public enum MsgLeiosBlockTxsSerializer implements Serializer<MsgLeiosBlockTxs> {
        INSTANCE;

        /** Decodes from the bytes as received: the payload is sliced, never re-encoded (ADR 0016 D2). */
        @Override
        public MsgLeiosBlockTxs deserialize(byte[] bytes) {
            Message message = LeiosFetchStateBase.deserialize(bytes);
            if (message instanceof MsgLeiosBlockTxs decoded)
                return decoded;
            throw notA("MsgLeiosBlockTxs", message);
        }

        @Override
        public byte[] serialize(MsgLeiosBlockTxs object) {
            return serializeArray(unsigned(4), LeiosCborUtil.validatedRawCborBytes(object.getTxList()));
        }

        /** Not supported: a decoded item has lost the payload's bytes as received. */
        @Override
        public MsgLeiosBlockTxs deserializeDI(DataItem di) {
            throw new UnsupportedOperationException("use deserialize(byte[]): the payload must keep its bytes");
        }
    }

    public enum MsgClientDoneSerializer implements Serializer<MsgClientDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgClientDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgClientDone deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 0, "MsgClientDone");
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

    private static IllegalArgumentException notA(String expected, Message message) {
        Throwable cause = message instanceof MsgLeiosFetchError error ? error.getCause() : null;
        return new IllegalArgumentException("Not a " + expected + ": " + message, cause);
    }
}
