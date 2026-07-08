package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class AppMsgSubmissionSerializers {

    // Tag 0: MsgInit [0, [chainId(tstr), ...]]
    public enum MsgInitSerializer implements Serializer<MsgInit> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgInit object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(serializeChainIds(object.getChainIds()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgInit deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 0)
                throw new CborRuntimeException("Invalid MsgInit label: " + label);
            return new MsgInit(deserializeChainIds(items));
        }
    }

    // Tag 6: MsgInitAck [6, [chainId(tstr), ...]]
    public enum MsgInitAckSerializer implements Serializer<MsgInitAck> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgInitAck object) {
            Array array = new Array();
            array.add(new UnsignedInteger(6));
            array.add(serializeChainIds(object.getChainIds()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgInitAck deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 6)
                throw new CborRuntimeException("Invalid MsgInitAck label: " + label);
            return new MsgInitAck(deserializeChainIds(items));
        }
    }

    private static Array serializeChainIds(List<String> chainIds) {
        Array chains = new Array();
        chains.setChunked(true);
        if (chainIds != null) {
            for (String chainId : chainIds) {
                chains.add(new UnicodeString(chainId));
            }
        }
        chains.add(SimpleValue.BREAK);
        return chains;
    }

    private static List<String> deserializeChainIds(List<DataItem> items) {
        List<String> chainIds = new ArrayList<>();
        if (items.size() > 1 && items.get(1) instanceof Array) {
            for (DataItem chainDI : ((Array) items.get(1)).getDataItems()) {
                if (chainDI instanceof Special) break;
                chainIds.add(((UnicodeString) chainDI).getString());
            }
        }
        return chainIds;
    }

    // Tag 1: MsgRequestMessageIds [1, isBlocking, ack, req]
    public enum MsgRequestMessageIdsSerializer implements Serializer<MsgRequestMessageIds> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgRequestMessageIds msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            array.add(msg.isBlocking() ? SimpleValue.TRUE : SimpleValue.FALSE);
            array.add(new UnsignedInteger(msg.getAckCount()));
            array.add(new UnsignedInteger(msg.getReqCount()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgRequestMessageIds deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 1)
                throw new CborRuntimeException("Invalid MsgRequestMessageIds label: " + label);
            boolean blocking = items.get(1) == SimpleValue.TRUE;
            short ack = ((UnsignedInteger) items.get(2)).getValue().shortValue();
            short req = ((UnsignedInteger) items.get(3)).getValue().shortValue();
            return new MsgRequestMessageIds(blocking, ack, req);
        }
    }

    // Tag 2: MsgReplyMessageIds [2, [[messageId, size], ...]]
    public enum MsgReplyMessageIdsSerializer implements Serializer<MsgReplyMessageIds> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgReplyMessageIds msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));

            Array pairs = new Array();
            pairs.setChunked(true);
            if (msg.getMessageIds() != null) {
                for (AppMessageId mid : msg.getMessageIds()) {
                    Array pair = new Array();
                    pair.add(new ByteString(mid.getMessageId()));
                    pair.add(new UnsignedInteger(mid.getSize()));
                    pairs.add(pair);
                }
            }
            pairs.add(SimpleValue.BREAK);
            array.add(pairs);
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgReplyMessageIds deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 2)
                throw new CborRuntimeException("Invalid MsgReplyMessageIds label: " + label);

            Array pairsArray = (Array) items.get(1);
            List<AppMessageId> messageIds = new ArrayList<>();
            for (DataItem pairDI : pairsArray.getDataItems()) {
                if (pairDI instanceof Special) break;
                Array pair = (Array) pairDI;
                byte[] msgId = ((ByteString) pair.getDataItems().get(0)).getBytes();
                int size = ((UnsignedInteger) pair.getDataItems().get(1)).getValue().intValue();
                messageIds.add(new AppMessageId(msgId, size));
            }
            return new MsgReplyMessageIds(messageIds);
        }
    }

    // Tag 3: MsgRequestMessages [3, [messageId, ...]]
    public enum MsgRequestMessagesSerializer implements Serializer<MsgRequestMessages> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgRequestMessages msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));

            Array idsArray = new Array();
            idsArray.setChunked(true);
            if (msg.getMessageIds() != null) {
                for (byte[] id : msg.getMessageIds()) {
                    idsArray.add(new ByteString(id));
                }
            }
            idsArray.add(Special.BREAK);
            array.add(idsArray);
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgRequestMessages deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 3)
                throw new CborRuntimeException("Invalid MsgRequestMessages label: " + label);

            Array idsArray = (Array) items.get(1);
            List<byte[]> messageIds = new ArrayList<>();
            for (DataItem idDI : idsArray.getDataItems()) {
                if (idDI instanceof Special) break;
                messageIds.add(((ByteString) idDI).getBytes());
            }
            return new MsgRequestMessages(messageIds);
        }
    }

    // Tag 4: MsgReplyMessages [4, [appMessage, ...]]
    public enum MsgReplyMessagesSerializer implements Serializer<MsgReplyMessages> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgReplyMessages msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(4));

            Array msgsArray = new Array();
            msgsArray.setChunked(true);
            if (msg.getMessages() != null) {
                for (AppMessage appMsg : msg.getMessages()) {
                    msgsArray.add(serializeAppMessage(appMsg));
                }
            }
            msgsArray.add(SimpleValue.BREAK);
            array.add(msgsArray);
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgReplyMessages deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 4)
                throw new CborRuntimeException("Invalid MsgReplyMessages label: " + label);

            Array msgsArray = (Array) items.get(1);
            List<AppMessage> messages = new ArrayList<>();
            for (DataItem msgDI : msgsArray.getDataItems()) {
                if (msgDI instanceof Special) break;
                messages.add(deserializeAppMessage((Array) msgDI));
            }
            return new MsgReplyMessages(messages);
        }
    }

    // Tag 5: MsgDone [5]
    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(5));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgDone deserialize(byte[] bytes) {
            DataItem di = CborSerializationUtil.deserializeOne(bytes);
            int key = ((UnsignedInteger) ((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 5)
                return new MsgDone();
            throw new CborRuntimeException("Invalid MsgDone label: " + key);
        }
    }

    // AppMessage envelope v2 CBOR:
    // [version(uint), messageId(bstr), chainId(tstr), topic(tstr), sender(bstr),
    //  senderSeq(uint), expiresAt(uint), body(bstr), [authScheme(uint), authProof(bstr)]]
    public static Array serializeAppMessage(AppMessage msg) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(msg.getVersion()));
        arr.add(new ByteString(msg.getMessageId()));
        arr.add(new UnicodeString(msg.getChainId() != null ? msg.getChainId() : ""));
        arr.add(new UnicodeString(msg.getTopic() != null ? msg.getTopic() : ""));
        arr.add(new ByteString(msg.getSender() != null ? msg.getSender() : new byte[0]));
        arr.add(new UnsignedInteger(msg.getSenderSeq()));
        arr.add(new UnsignedInteger(msg.getExpiresAt()));
        arr.add(new ByteString(msg.getBody() != null ? msg.getBody() : new byte[0]));

        Array auth = new Array();
        auth.add(new UnsignedInteger(msg.getAuthScheme()));
        auth.add(new ByteString(msg.getAuthProof() != null ? msg.getAuthProof() : new byte[0]));
        arr.add(auth);
        return arr;
    }

    public static AppMessage deserializeAppMessage(Array arr) {
        List<DataItem> items = arr.getDataItems();
        int version = ((UnsignedInteger) items.get(0)).getValue().intValue();
        byte[] messageId = ((ByteString) items.get(1)).getBytes();
        String chainId = ((UnicodeString) items.get(2)).getString();
        String topic = ((UnicodeString) items.get(3)).getString();
        byte[] sender = ((ByteString) items.get(4)).getBytes();
        long senderSeq = ((UnsignedInteger) items.get(5)).getValue().longValue();
        long expiresAt = ((UnsignedInteger) items.get(6)).getValue().longValue();
        byte[] body = ((ByteString) items.get(7)).getBytes();

        Array auth = (Array) items.get(8);
        int authScheme = ((UnsignedInteger) auth.getDataItems().get(0)).getValue().intValue();
        byte[] authProof = ((ByteString) auth.getDataItems().get(1)).getBytes();

        return AppMessage.builder()
                .version(version)
                .messageId(messageId)
                .chainId(chainId)
                .topic(topic)
                .sender(sender)
                .senderSeq(senderSeq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(authScheme)
                .authProof(authProof)
                .build();
    }
}
