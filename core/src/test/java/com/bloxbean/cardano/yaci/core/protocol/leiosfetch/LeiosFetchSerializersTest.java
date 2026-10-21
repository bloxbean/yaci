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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosFetchSerializersTest {

    @Test
    void serializesW42Tags() {
        // cardano-blueprint main @ 0460c5a6: Done=0, BlockRequest=1, Block=2, BlockTxsRequest=3, BlockTxs=4
        assertEquals("8100", HexUtil.encodeHexString(new MsgClientDone().serialize()));
        assertEquals(0x01, new MsgLeiosBlockRequest(point(1)).serialize()[1] & 0xff);
        assertEquals(0x02, new MsgLeiosBlock(LeiosCborUtil.toRawCbor(rawArray(1, 2))).serialize()[1] & 0xff);
        assertEquals(0x03, new MsgLeiosBlockTxsRequest(point(1), LeiosTxBitmap.firstN(1)).serialize()[1] & 0xff);
        assertEquals("82048102", HexUtil.encodeHexString(
                new MsgLeiosBlockTxs(LeiosCborUtil.toRawCbor(singletonArray(2))).serialize()));
    }

    @Test
    void decodesW42BlockTxsWithTag24Transactions() {
        // [4, [24(h'80'), 24(h'8101')]]: two CBOR-in-CBOR transactions, no echoed point or bitmap
        byte[] wire = HexUtil.decodeHexString("820482d8184180d818428101");

        MsgLeiosBlockTxs decoded = assertInstanceOf(MsgLeiosBlockTxs.class, LeiosFetchStateBase.deserialize(wire));

        assertEquals("82d8184180d818428101", HexUtil.encodeHexString(decoded.getTxList().getCbor()));
        assertArrayEquals(wire, decoded.serialize());
    }

    @Test
    void decodesW42BlockTxsWithIndefiniteList() {
        //The Haskell decoder accepts a definite or an indefinite list (decodeListLenOrIndef)
        byte[] wire = HexUtil.decodeHexString("82049fd8184180d818428101ff");

        MsgLeiosBlockTxs decoded = assertInstanceOf(MsgLeiosBlockTxs.class, LeiosFetchStateBase.deserialize(wire));

        assertArrayEquals(wire, decoded.serialize());
        assertEquals(2, com.bloxbean.cardano.yaci.core.model.serializers.leios.EndorserBlockTxListSerializer.INSTANCE
                .deserialize(decoded.getTxList().getCbor()).size());
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

        assertTrue(HexUtil.encodeHexString(request.serialize()).contains("bfff"));

        Message decoded = LeiosFetchStateBase.deserialize(request.serialize());
        MsgLeiosBlockTxsRequest decodedRequest = assertInstanceOf(MsgLeiosBlockTxsRequest.class, decoded);
        assertEquals(LeiosTxBitmap.empty(), decodedRequest.getBitmap());
    }

    @Test
    void serializesAndDeserializesBlockTxsAsOpaqueCbor() {
        LeiosRawCbor txList = LeiosCborUtil.toRawCbor(rawArray(20, 21));
        MsgLeiosBlockTxs blockTxs = new MsgLeiosBlockTxs(txList);

        MsgLeiosBlockTxs decoded = LeiosFetchSerializers.MsgLeiosBlockTxsSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(blockTxs.serialize()));

        assertEquals(txList, decoded.getTxList());
    }

    @Test
    void rejectsMessagesWithWrongArity() {
        Array blockRequest = new Array();
        blockRequest.add(new UnsignedInteger(1));

        Array done = new Array();
        done.add(new UnsignedInteger(0));
        done.add(new UnsignedInteger(1));

        assertThrows(IllegalArgumentException.class,
                () -> LeiosFetchSerializers.MsgLeiosBlockRequestSerializer.INSTANCE.deserializeDI(blockRequest));
        assertThrows(IllegalArgumentException.class,
                () -> LeiosFetchSerializers.MsgClientDoneSerializer.INSTANCE.deserializeDI(done));
    }

    @Test
    void deserializesChunkedArraysWithBreakItems() {
        Array done = new Array();
        done.add(new UnsignedInteger(0));
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
    void unsupportedTagsReturnErrorMessage() {
        Array array = new Array();
        array.add(new UnsignedInteger(5));

        Message decoded = LeiosFetchStateBase.deserialize(CborSerializationUtil.serialize(array, false));

        MsgLeiosFetchError error = assertInstanceOf(MsgLeiosFetchError.class, decoded);
        assertInstanceOf(UnsupportedOperationException.class, error.getCause());
    }

    private Array singletonArray(long value) {
        Array array = new Array();
        array.add(new UnsignedInteger(value));
        return array;
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
