package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgCanceled;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockAnnouncement;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosBlockTxsOffer;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotificationRequestNext;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosVotes;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgQuit;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.math.BigInteger;
import java.util.List;

/**
 * LeiosNotify (mini-protocol 18) codecs of Musashi rc-2026w42: {@code LeiosDemoOnlyTestNotify.hs} at
 * ouroboros-consensus 840e789b, matching cardano-blueprint {@code main} @ 0460c5a6:
 * Done=0, Quit=1, Canceled=2, RequestNext=3, BlockAnnouncement=4, BlockOffer=5, BlockTxsOffer=6, Votes=7.
 */
public class LeiosNotifySerializers {
    private static final BigInteger WORD32_MAX = BigInteger.valueOf(MsgLeiosBlockOffer.MAX_EB_SIZE);

    public enum MsgLeiosNotificationRequestNextSerializer
            implements Serializer<MsgLeiosNotificationRequestNext> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosNotificationRequestNext object) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosNotificationRequestNext deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 3, "MsgLeiosNotificationRequestNext");
            requireArity(items, 1, "MsgLeiosNotificationRequestNext");
            return new MsgLeiosNotificationRequestNext();
        }
    }

    public enum MsgLeiosBlockAnnouncementSerializer implements Serializer<MsgLeiosBlockAnnouncement> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockAnnouncement object) {
            Array array = new Array();
            array.add(new UnsignedInteger(4));
            array.add(LeiosCborUtil.fromRawCbor(object.getAnnouncement()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockAnnouncement deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 4, "MsgLeiosBlockAnnouncement");
            requireArity(items, 2, "MsgLeiosBlockAnnouncement");
            return new MsgLeiosBlockAnnouncement(LeiosCborUtil.toRawCbor(items.get(1)));
        }
    }

    public enum MsgLeiosBlockOfferSerializer implements Serializer<MsgLeiosBlockOffer> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosBlockOffer object) {
            Array array = new Array();
            array.add(new UnsignedInteger(5));
            array.add(LeiosCborUtil.serializePointArray(object.getPoint()));
            array.add(new UnsignedInteger(object.getEbSize()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockOffer deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 5, "MsgLeiosBlockOffer");
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
            array.add(new UnsignedInteger(6));
            array.add(LeiosCborUtil.serializePointArray(object.getPoint()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosBlockTxsOffer deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 6, "MsgLeiosBlockTxsOffer");
            requireArity(items, 2, "MsgLeiosBlockTxsOffer");
            return new MsgLeiosBlockTxsOffer(LeiosCborUtil.deserializePointArray(items.get(1)));
        }
    }

    public enum MsgLeiosVotesSerializer implements Serializer<MsgLeiosVotes> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgLeiosVotes object) {
            Array array = new Array();
            array.add(new UnsignedInteger(7));
            array.add(LeiosCborUtil.serializeRawCborArray(object.getVotes()));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgLeiosVotes deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 7, "MsgLeiosVotes");
            requireArity(items, 2, "MsgLeiosVotes");
            return new MsgLeiosVotes(LeiosCborUtil.deserializeRawCborArrayItems(items.get(1)));
        }
    }

    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgDone deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 0, "MsgDone");
            requireArity(items, 1, "MsgDone");
            return new MsgDone();
        }
    }

    public enum MsgQuitSerializer implements Serializer<MsgQuit> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgQuit object) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgQuit deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 1, "MsgQuit");
            requireArity(items, 1, "MsgQuit");
            return new MsgQuit();
        }
    }

    public enum MsgCanceledSerializer implements Serializer<MsgCanceled> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgCanceled object) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));
            return CborSerializationUtil.serialize(array, false);
        }

        @Override
        public MsgCanceled deserializeDI(DataItem di) {
            List<DataItem> items = messageItems(di, 2, "MsgCanceled");
            requireArity(items, 1, "MsgCanceled");
            return new MsgCanceled();
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
