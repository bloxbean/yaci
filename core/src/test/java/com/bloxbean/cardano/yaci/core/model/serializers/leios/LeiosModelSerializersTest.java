package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlock;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosCertificate;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVote;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVoteFormat;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosModelSerializersTest {

    @Test
    void decodesBareEndorserBlockMap() {
        byte[] hash = bytes(32, 1);
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new ByteString(hash), new UnsignedInteger(42));

        EndorserBlock endorserBlock = EndorserBlockSerializer.INSTANCE.deserialize(
                CborSerializationUtil.serialize(map, false));

        assertEquals(1, endorserBlock.txCount());
        assertEquals(42, endorserBlock.totalTxBytes());
        assertEquals(HexUtil.encodeHexString(hash), endorserBlock.getTxRefs().get(0).getTxHash());
    }

    @Test
    void decodesArrayWrappedOrderedMapEndorserBlock() {
        byte[] hash = bytes(32, 2);
        Array pair = new Array();
        pair.add(new ByteString(hash));
        pair.add(new UnsignedInteger(12));

        Array orderedMap = new Array();
        orderedMap.add(pair);

        Array wrapped = new Array();
        wrapped.add(orderedMap);

        EndorserBlock endorserBlock = EndorserBlockSerializer.INSTANCE.deserialize(
                CborSerializationUtil.serialize(wrapped, false));

        assertEquals(1, endorserBlock.txCount());
        assertEquals(12, endorserBlock.totalTxBytes());
    }

    @Test
    void decodesArrayWrappedTaggedOrderedMapEndorserBlock() {
        byte[] hash = bytes(32, 8);
        Array pair = new Array();
        pair.add(new ByteString(hash));
        pair.add(new UnsignedInteger(21));

        Array orderedMap = new Array();
        orderedMap.setTag(258L);
        orderedMap.add(pair);

        Array wrapped = new Array();
        wrapped.add(orderedMap);

        EndorserBlock endorserBlock = EndorserBlockSerializer.INSTANCE.deserialize(
                CborSerializationUtil.serialize(wrapped, false));

        assertEquals(1, endorserBlock.txCount());
        assertEquals(21, endorserBlock.totalTxBytes());
    }

    @Test
    void rejectsDuplicateEndorserBlockTxHashesBeforeMapMaterializationLosesThem() {
        byte[] hash = bytes(32, 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xa2);
        writeByteString(out, hash);
        out.write(0x01);
        writeByteString(out, hash);
        out.write(0x02);

        assertThrows(IllegalArgumentException.class,
                () -> EndorserBlockSerializer.INSTANCE.deserialize(out.toByteArray()));
    }

    @Test
    void decodesSlotEbHashVote() {
        Array vote = new Array();
        vote.add(new UnsignedInteger(100));
        vote.add(new ByteString(bytes(32, 4)));
        vote.add(new UnsignedInteger(2));
        vote.add(new ByteString(bytes(48, 5)));

        LeiosVote decoded = LeiosVoteSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(vote, false));

        assertEquals(LeiosVoteFormat.SLOT_EB_HASH, decoded.getFormat());
        assertEquals(100, decoded.getSlot());
        assertEquals(2, decoded.getVoterId());
    }

    @Test
    void decodesAnnouncingRbHashVote() {
        Array vote = new Array();
        vote.add(new ByteString(bytes(32, 6)));
        vote.add(new UnsignedInteger(3));
        vote.add(new ByteString(bytes(48, 7)));

        LeiosVote decoded = LeiosVoteSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(vote, false));

        assertEquals(LeiosVoteFormat.ANNOUNCING_RB_HASH, decoded.getFormat());
        assertEquals(3, decoded.getVoterId());
    }

    @Test
    void unknownVoteShapeDegradesToRaw() {
        Array vote = new Array();
        vote.add(SimpleValue.NULL);

        LeiosVote decoded = LeiosVoteSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(vote, false));

        assertEquals(LeiosVoteFormat.UNKNOWN, decoded.getFormat());
        assertTrue(decoded.getCbor().length() > 0);
    }

    @Test
    void decodesTxListWithDedicatedNs8EraMapping() {
        byte[] txBytes = new byte[]{(byte) 0x84, 1, 2, 3, 4};
        ByteString txCbor = new ByteString(txBytes);
        txCbor.setTag(24L);

        Array tx = new Array();
        tx.add(new UnsignedInteger(7));
        tx.add(txCbor);

        Array txList = new Array();
        txList.add(tx);

        List<EndorserBlockTx> decoded = EndorserBlockTxListSerializer.INSTANCE.deserialize(
                CborSerializationUtil.serialize(txList, false));

        assertEquals(1, decoded.size());
        assertEquals(7, decoded.get(0).getTxEraIndex());
        assertEquals(Era.Dijkstra, decoded.get(0).getEra());
        assertEquals(HexUtil.encodeHexString(txBytes), decoded.get(0).getTxCbor());
        assertTrue(decoded.get(0).isParsed());
    }

    @Test
    void malformedTxListItemFallsBackToRaw() {
        Array txList = new Array();
        txList.add(new UnsignedInteger(1));

        List<EndorserBlockTx> decoded = EndorserBlockTxListSerializer.INSTANCE.deserialize(
                CborSerializationUtil.serialize(txList, false));

        assertEquals(1, decoded.size());
        assertFalse(decoded.get(0).isParsed());
        assertEquals(-1, decoded.get(0).getTxEraIndex());
    }

    @Test
    void decodesCertificateBestEffortAndPreservesRawCbor() {
        Array certificate = new Array();
        certificate.add(new ByteString(bytes(8, 1)));
        certificate.add(new ByteString(bytes(48, 2)));

        byte[] cbor = CborSerializationUtil.serialize(certificate, false);
        LeiosCertificate decoded = LeiosCertificateSerializer.INSTANCE.deserialize(cbor);

        assertEquals(HexUtil.encodeHexString(cbor), decoded.getCbor());
        assertEquals(HexUtil.encodeHexString(bytes(8, 1)), decoded.getSigners());
        assertEquals(HexUtil.encodeHexString(bytes(48, 2)), decoded.getAggregatedSignature());
    }

    private void writeByteString(ByteArrayOutputStream out, byte[] bytes) {
        out.write(0x58);
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}
