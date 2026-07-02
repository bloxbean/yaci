package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlock;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxsRequest;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosFetchError;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosFetchSerializersTest {

    @Test
    void serializesDoneTag() {
        assertEquals("8109", HexUtil.encodeHexString(new MsgClientDone().serialize()));
    }

    @Test
    void serializesAndDeserializesPrototypeBlockRequest() {
        LeiosPoint point = point(1);
        MsgLeiosBlockRequest request = new MsgLeiosBlockRequest(point);

        MsgLeiosBlockRequest decoded = LeiosFetchSerializers.MsgLeiosBlockRequestSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(request.serialize()));

        assertEquals(point, decoded.getPoint());
    }

    @Test
    void serializesAndDeserializesBlockAsOpaqueCbor() {
        LeiosRawCbor endorserBlock = LeiosCborUtil.toRawCbor(rawArray(10, 11));
        MsgLeiosBlock block = new MsgLeiosBlock(endorserBlock);

        Message decoded = LeiosFetchStateBase.deserialize(block.serialize());

        MsgLeiosBlock decodedBlock = assertInstanceOf(MsgLeiosBlock.class, decoded);
        assertEquals(endorserBlock, decodedBlock.getEndorserBlock());
    }

    @Test
    void serializesAndDeserializesPrototypeBlockTxsRequest() {
        LeiosPoint point = point(2);
        LeiosTxBitmap bitmap = LeiosTxBitmap.fromIndices(0, 63, 64, 127);
        MsgLeiosBlockTxsRequest request = new MsgLeiosBlockTxsRequest(point, bitmap);

        MsgLeiosBlockTxsRequest decoded = LeiosFetchSerializers.MsgLeiosBlockTxsRequestSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(request.serialize()));

        assertEquals(point, decoded.getPoint());
        assertEquals(bitmap, decoded.getBitmap());
        assertTrue(HexUtil.encodeHexString(request.serialize()).contains("bf"));
    }

    @Test
    void serializesEmptyBitmapFieldsAsIndefiniteMaps() {
        MsgLeiosBlockTxsRequest request =
                new MsgLeiosBlockTxsRequest(point(6), LeiosTxBitmap.empty());
        MsgLeiosBlockTxs response = new MsgLeiosBlockTxs(
                point(7), LeiosTxBitmap.empty(), LeiosCborUtil.toRawCbor(rawArray(30, 31)));

        assertTrue(HexUtil.encodeHexString(request.serialize()).contains("bfff"));
        assertTrue(HexUtil.encodeHexString(response.serialize()).contains("bfff"));

        Message decoded = LeiosFetchStateBase.deserialize(request.serialize());
        MsgLeiosBlockTxsRequest decodedRequest = assertInstanceOf(MsgLeiosBlockTxsRequest.class, decoded);
        assertEquals(LeiosTxBitmap.empty(), decodedRequest.getBitmap());
    }

    @Test
    void serializesAndDeserializesPrototypeBlockTxsAsOpaqueCbor() {
        LeiosPoint point = point(3);
        LeiosTxBitmap bitmap = LeiosTxBitmap.firstN(65);
        LeiosRawCbor txList = LeiosCborUtil.toRawCbor(rawArray(20, 21));
        MsgLeiosBlockTxs blockTxs = new MsgLeiosBlockTxs(point, bitmap, txList);

        MsgLeiosBlockTxs decoded = LeiosFetchSerializers.MsgLeiosBlockTxsSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(blockTxs.serialize()));

        assertEquals(point, decoded.getPoint());
        assertEquals(bitmap, decoded.getBitmap());
        assertEquals(txList, decoded.getTxList());
    }

    @Test
    void rejectsMessagesWithWrongArity() {
        Array blockRequest = new Array();
        blockRequest.add(new UnsignedInteger(0));

        Array done = new Array();
        done.add(new UnsignedInteger(9));
        done.add(new UnsignedInteger(1));

        assertThrows(IllegalArgumentException.class,
                () -> LeiosFetchSerializers.MsgLeiosBlockRequestSerializer.INSTANCE.deserializeDI(blockRequest));
        assertThrows(IllegalArgumentException.class,
                () -> LeiosFetchSerializers.MsgClientDoneSerializer.INSTANCE.deserializeDI(done));
    }

    @Test
    void deserializesChunkedArraysWithBreakItems() {
        Array done = new Array();
        done.add(new UnsignedInteger(9));
        done.add(SimpleValue.BREAK);

        assertInstanceOf(MsgClientDone.class,
                LeiosFetchSerializers.MsgClientDoneSerializer.INSTANCE.deserializeDI(done));
    }

    @Test
    void trailingTopLevelCborReturnsErrorMessage() {
        byte[] done = new MsgClientDone().serialize();
        byte[] cborSequence = new byte[]{done[0], done[1], 0x01};

        Message decoded = LeiosFetchStateBase.deserialize(cborSequence);

        assertInstanceOf(MsgLeiosFetchError.class, decoded);
    }

    @Test
    void unsupportedRangeTagsReturnErrorMessage() {
        Array array = new Array();
        array.add(new UnsignedInteger(4));

        Message decoded = LeiosFetchStateBase.deserialize(CborSerializationUtil.serialize(array, false));

        MsgLeiosFetchError error = assertInstanceOf(MsgLeiosFetchError.class, decoded);
        assertInstanceOf(UnsupportedOperationException.class, error.getCause());
    }

    private Array rawArray(long first, long second) {
        Array array = new Array();
        array.add(new UnsignedInteger(first));
        array.add(new UnsignedInteger(second));
        return array;
    }

    private LeiosPoint point(int seed) {
        byte[] hash = new byte[LeiosPoint.EB_HASH_LENGTH];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return new LeiosPoint(100 + seed, hash);
    }
}
