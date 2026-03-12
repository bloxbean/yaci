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

    // Tag 0: MsgInit
    public enum MsgInitSerializer implements Serializer<MsgInit> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgInit object) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgInit deserializeDI(DataItem di) {
            Array array = (Array) di;
            int label = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            if (label == 0)
                return new MsgInit();
            throw new CborRuntimeException("Invalid MsgInit label: " + label);
        }
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

    // AppMessage CBOR: [messageId(bstr), messageBody(bstr), authMethod(uint), authProof(bstr), topicId(tstr), expiresAt(uint)]
    public static Array serializeAppMessage(AppMessage msg) {
        Array arr = new Array();
        arr.add(new ByteString(msg.getMessageId()));
        arr.add(new ByteString(msg.getMessageBody()));
        arr.add(new UnsignedInteger(msg.getAuthMethod()));
        arr.add(new ByteString(msg.getAuthProof() != null ? msg.getAuthProof() : new byte[0]));
        arr.add(new UnicodeString(msg.getTopicId() != null ? msg.getTopicId() : ""));
        arr.add(new UnsignedInteger(msg.getExpiresAt()));
        return arr;
    }

    public static AppMessage deserializeAppMessage(Array arr) {
        List<DataItem> items = arr.getDataItems();
        byte[] messageId = ((ByteString) items.get(0)).getBytes();
        byte[] messageBody = ((ByteString) items.get(1)).getBytes();
        int authMethod = ((UnsignedInteger) items.get(2)).getValue().intValue();
        byte[] authProof = ((ByteString) items.get(3)).getBytes();
        String topicId = ((UnicodeString) items.get(4)).getString();
        long expiresAt = ((UnsignedInteger) items.get(5)).getValue().longValue();

        return AppMessage.builder()
                .messageId(messageId)
                .messageBody(messageBody)
                .authMethod(authMethod)
                .authProof(authProof)
                .topicId(topicId)
                .expiresAt(expiresAt)
                .build();
    }
}
