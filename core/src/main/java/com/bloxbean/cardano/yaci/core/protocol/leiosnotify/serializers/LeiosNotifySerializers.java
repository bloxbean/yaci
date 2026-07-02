package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgClientDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.math.BigInteger;
import java.util.List;

public class LeiosNotifySerializers {
    private static final BigInteger WORD32_MAX = BigInteger.valueOf(MsgLeiosBlockOffer.MAX_EB_SIZE);

    public enum MsgLeiosNotificationRequestNextSerializer
            implements Serializer<MsgLeiosNotificationRequestNext> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosNotificationRequestNext object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosNotificationRequestNext deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 0, "MsgLeiosNotificationRequestNext");
            requireArity(items, 1, "MsgLeiosNotificationRequestNext");
            return new MsgLeiosNotificationRequestNext();
        }
    }

    public enum MsgLeiosBlockAnnouncementSerializer implements Serializer<MsgLeiosBlockAnnouncement> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockAnnouncement object) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            array.add(LeiosCborUtil.fromRawCbor(object.getAnnouncement()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockAnnouncement deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 1, "MsgLeiosBlockAnnouncement");
            requireArity(items, 2, "MsgLeiosBlockAnnouncement");
            return new MsgLeiosBlockAnnouncement(LeiosCborUtil.toRawCbor(items.get(1)));
        }
    }

    public enum MsgLeiosBlockOfferSerializer implements Serializer<MsgLeiosBlockOffer> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockOffer object) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));
            array.add(LeiosCborUtil.serializePointArray(object.getPoint()));
            array.add(new UnsignedInteger(object.getEbSize()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockOffer deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 2, "MsgLeiosBlockOffer");
            requireArity(items, 3, "MsgLeiosBlockOffer");
            LeiosPoint point = LeiosCborUtil.deserializePointArray(items.get(1));
            long ebSize = toWord32((UnsignedInteger) items.get(2));
            return new MsgLeiosBlockOffer(point, ebSize);
        }
    }

    public enum MsgLeiosBlockTxsOfferSerializer implements Serializer<MsgLeiosBlockTxsOffer> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockTxsOffer object) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));
            array.add(LeiosCborUtil.serializePointArray(object.getPoint()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockTxsOffer deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 3, "MsgLeiosBlockTxsOffer");
            requireArity(items, 2, "MsgLeiosBlockTxsOffer");
            return new MsgLeiosBlockTxsOffer(LeiosCborUtil.deserializePointArray(items.get(1)));
        }
    }

    public enum MsgLeiosVotesSerializer implements Serializer<MsgLeiosVotes> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosVotes object) {
            Array array = new Array();
            array.add(new UnsignedInteger(4));
            array.add(LeiosCborUtil.serializeRawCborArray(object.getVotes()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosVotes deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 4, "MsgLeiosVotes");
            requireArity(items, 2, "MsgLeiosVotes");
            return new MsgLeiosVotes(LeiosCborUtil.deserializeRawCborArrayItems(items.get(1)));
        }
    }

    public enum MsgClientDoneSerializer implements Serializer<MsgClientDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgClientDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(5));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgClientDone deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 5, "MsgClientDone");
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

    private static long toWord32(UnsignedInteger value) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.compareTo(WORD32_MAX) > 0) {
            throw new IllegalArgumentException("value must fit in word32");
        }
        return bigint.longValue();
    }
}
