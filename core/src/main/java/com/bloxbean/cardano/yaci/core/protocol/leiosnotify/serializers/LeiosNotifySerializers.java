package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages.MsgLeiosNotifyError;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyStateBase;

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

        /** Decodes from the bytes as received: the payload is sliced, never re-encoded (ADR 0016 D2). */
        @Override
        public MsgLeiosBlockAnnouncement deserialize(byte[] bytes) {
            Message message = LeiosNotifyStateBase.deserialize(bytes);
            if (message instanceof MsgLeiosBlockAnnouncement decoded)
                return decoded;
            throw notA("MsgLeiosBlockAnnouncement", message);
        }

        @Override
        public byte[] serialize(MsgLeiosBlockAnnouncement object) {
            // the announcement goes out as it is held, never re-encoded
            return LeiosCborUtil.concat(new byte[]{(byte) 0x82, 0x04}, object.getAnnouncement().getCbor());
        }

        /** Not supported: a decoded item has lost the payload's bytes as received. */
        @Override
        public MsgLeiosBlockAnnouncement deserializeDI(DataItem di) {
            throw new UnsupportedOperationException("use deserialize(byte[]): the payload must keep its bytes");
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

        /** Decodes from the bytes as received: the payload is sliced, never re-encoded (ADR 0016 D2). */
        @Override
        public MsgLeiosVotes deserialize(byte[] bytes) {
            Message message = LeiosNotifyStateBase.deserialize(bytes);
            if (message instanceof MsgLeiosVotes decoded)
                return decoded;
            throw notA("MsgLeiosVotes", message);
        }

        @Override
        public byte[] serialize(MsgLeiosVotes object) {
            // the votes go out as they are held, never re-encoded
            byte[] list = object.getVoteList() != null ? object.getVoteList().getCbor()
                    : LeiosCborUtil.rawArray(object.getVotes());
            return LeiosCborUtil.concat(new byte[]{(byte) 0x82, 0x07}, list);
        }

        /** Not supported: a decoded item has lost the payload's bytes as received. */
        @Override
        public MsgLeiosVotes deserializeDI(DataItem di) {
            throw new UnsupportedOperationException("use deserialize(byte[]): the payload must keep its bytes");
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

    private static IllegalArgumentException notA(String expected, Message message) {
        Throwable cause = message instanceof MsgLeiosNotifyError error ? error.getCause() : null;
        return new IllegalArgumentException("Not a " + expected + ": " + message, cause);
    }
}
