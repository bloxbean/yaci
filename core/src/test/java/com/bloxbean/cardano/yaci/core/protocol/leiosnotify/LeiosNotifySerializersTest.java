package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotifyError;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeiosNotifySerializersTest {

    @Test
    void serializesRequestNextAndDoneTags() {
        assertEquals("8100", HexUtil.encodeHexString(new MsgLeiosNotificationRequestNext().serialize()));
        assertEquals("8105", HexUtil.encodeHexString(new MsgClientDone().serialize()));
    }

    @Test
    void rejectsRequestNextAndDoneWithExtraFields() {
        Array requestNext = new Array();
        requestNext.add(new UnsignedInteger(0));
        requestNext.add(new UnsignedInteger(1));

        Array done = new Array();
        done.add(new UnsignedInteger(5));
        done.add(new UnsignedInteger(1));

        assertThrows(IllegalArgumentException.class,
                () -> LeiosNotifySerializers.MsgLeiosNotificationRequestNextSerializer.INSTANCE
                        .deserializeDI(requestNext));
        assertThrows(IllegalArgumentException.class,
                () -> LeiosNotifySerializers.MsgClientDoneSerializer.INSTANCE.deserializeDI(done));
    }

    @Test
    void deserializesChunkedArraysWithBreakItems() {
        Array requestNext = new Array();
        requestNext.add(new UnsignedInteger(0));
        requestNext.add(SimpleValue.BREAK);

        assertInstanceOf(MsgLeiosNotificationRequestNext.class,
                LeiosNotifySerializers.MsgLeiosNotificationRequestNextSerializer.INSTANCE
                        .deserializeDI(requestNext));
    }

    @Test
    void serializesAndDeserializesBlockAnnouncementAsOpaqueCbor() {
        LeiosRawCbor announcement = LeiosCborUtil.toRawCbor(rawArray(7, 8));
        MsgLeiosBlockAnnouncement message = new MsgLeiosBlockAnnouncement(announcement);

        Message decoded = LeiosNotifyStateBase.deserialize(message.serialize());

        MsgLeiosBlockAnnouncement decodedAnnouncement =
                assertInstanceOf(MsgLeiosBlockAnnouncement.class, decoded);
        assertEquals(announcement, decodedAnnouncement.getAnnouncement());
    }

    @Test
    void serializesAndDeserializesPrototypeBlockOffer() {
        LeiosPoint point = point(11);
        MsgLeiosBlockOffer offer = new MsgLeiosBlockOffer(point, 1234);

        MsgLeiosBlockOffer decoded = LeiosNotifySerializers.MsgLeiosBlockOfferSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(offer.serialize()));

        assertEquals(point, decoded.getPoint());
        assertEquals(1234, decoded.getEbSize());
    }

    @Test
    void serializesAndDeserializesPrototypeBlockTxsOffer() {
        LeiosPoint point = point(12);
        MsgLeiosBlockTxsOffer offer = new MsgLeiosBlockTxsOffer(point);

        MsgLeiosBlockTxsOffer decoded = LeiosNotifySerializers.MsgLeiosBlockTxsOfferSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(offer.serialize()));

        assertEquals(point, decoded.getPoint());
    }

    @Test
    void serializesAndDeserializesPrototypeVotesAsOpaqueItems() {
        LeiosRawCbor vote = LeiosCborUtil.toRawCbor(voteArray(15, point(15).getEbHash(), 1));
        MsgLeiosVotes votes = new MsgLeiosVotes(List.of(vote));

        MsgLeiosVotes decoded = LeiosNotifySerializers.MsgLeiosVotesSerializer.INSTANCE
                .deserializeDI(CborSerializationUtil.deserializeOne(votes.serialize()));

        assertEquals(List.of(vote), decoded.getVotes());
    }

    @Test
    void rejectsPrototypeBlockOfferWithoutEbSize() {
        Array array = new Array();
        array.add(new UnsignedInteger(2));
        array.add(LeiosCborUtil.serializePointArray(point(1)));

        assertThrows(IllegalArgumentException.class, () -> {
            var encoded = CborSerializationUtil.serialize(array, false);
            var dataItem = CborSerializationUtil.deserializeOne(encoded);
            LeiosNotifySerializers.MsgLeiosBlockOfferSerializer.INSTANCE.deserializeDI(dataItem);
        });
    }

    @Test
    void trailingTopLevelCborReturnsErrorMessage() {
        byte[] requestNext = new MsgLeiosNotificationRequestNext().serialize();
        byte[] cborSequence = new byte[]{requestNext[0], requestNext[1], 0x01};

        Message decoded = LeiosNotifyStateBase.deserialize(cborSequence);

        assertInstanceOf(MsgLeiosNotifyError.class, decoded);
    }

    @Test
    void protectsMessagePayloadsFromMutation() {
        byte[] hash = point(5).getEbHash();
        LeiosRawCbor vote = LeiosCborUtil.toRawCbor(voteArray(5, hash, 1));
        MsgLeiosVotes votes = new MsgLeiosVotes(List.of(vote));

        assertThrows(UnsupportedOperationException.class, () -> votes.getVotes().add(vote));
        assertArrayEquals(vote.getCbor(), votes.getVotes().get(0).getCbor());
    }

    private Array rawArray(long first, long second) {
        Array array = new Array();
        array.add(new UnsignedInteger(first));
        array.add(new UnsignedInteger(second));
        return array;
    }

    private Array voteArray(long slot, byte[] hash, int voterId) {
        Array array = new Array();
        array.add(new UnsignedInteger(slot));
        array.add(new ByteString(hash));
        array.add(new UnsignedInteger(voterId));
        array.add(new ByteString(new byte[48]));
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
