package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.ArrayList;
import java.util.List;

public class LocalAppMsgNotifySerializers {

    // Tag 0: MsgRequestMessages [0, isBlocking]
    public enum MsgRequestMessagesSerializer implements Serializer<MsgRequestMessages> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgRequestMessages msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(msg.isBlocking() ? SimpleValue.TRUE : SimpleValue.FALSE);
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgRequestMessages deserializeDI(DataItem di) {
            Array array = (Array) di;
            java.util.List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 0)
                throw new CborRuntimeException("Invalid MsgRequestMessages label: " + label);
            boolean blocking = items.get(1) == SimpleValue.TRUE;
            return new MsgRequestMessages(blocking);
        }
    }

    // Tag 1: MsgReplyMessagesNonBlocking [1, [msgs], hasMore]
    public enum MsgReplyMessagesNonBlockingSerializer implements Serializer<MsgReplyMessagesNonBlocking> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgReplyMessagesNonBlocking msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));

            Array msgsArray = new Array();
            msgsArray.setChunked(true);
            if (msg.getMessages() != null) {
                for (AppMessage appMsg : msg.getMessages()) {
                    msgsArray.add(AppMsgSubmissionSerializers.serializeAppMessage(appMsg));
                }
            }
            msgsArray.add(SimpleValue.BREAK);
            array.add(msgsArray);
            array.add(msg.isHasMore() ? SimpleValue.TRUE : SimpleValue.FALSE);
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgReplyMessagesNonBlocking deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 1)
                throw new CborRuntimeException("Invalid MsgReplyMessagesNonBlocking label: " + label);

            Array msgsArray = (Array) items.get(1);
            List<AppMessage> messages = new ArrayList<>();
            for (DataItem msgDI : msgsArray.getDataItems()) {
                if (msgDI instanceof Special) break;
                messages.add(AppMsgSubmissionSerializers.deserializeAppMessage((Array) msgDI));
            }
            boolean hasMore = items.get(2) == SimpleValue.TRUE;
            return new MsgReplyMessagesNonBlocking(messages, hasMore);
        }
    }

    // Tag 2: MsgReplyMessagesBlocking [2, [msgs]]
    public enum MsgReplyMessagesBlockingSerializer implements Serializer<MsgReplyMessagesBlocking> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgReplyMessagesBlocking msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));

            Array msgsArray = new Array();
            msgsArray.setChunked(true);
            if (msg.getMessages() != null) {
                for (AppMessage appMsg : msg.getMessages()) {
                    msgsArray.add(AppMsgSubmissionSerializers.serializeAppMessage(appMsg));
                }
            }
            msgsArray.add(SimpleValue.BREAK);
            array.add(msgsArray);
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgReplyMessagesBlocking deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 2)
                throw new CborRuntimeException("Invalid MsgReplyMessagesBlocking label: " + label);

            Array msgsArray = (Array) items.get(1);
            List<AppMessage> messages = new ArrayList<>();
            for (DataItem msgDI : msgsArray.getDataItems()) {
                if (msgDI instanceof Special) break;
                messages.add(AppMsgSubmissionSerializers.deserializeAppMessage((Array) msgDI));
            }
            return new MsgReplyMessagesBlocking(messages);
        }
    }

    // Tag 3: MsgClientDone [3]
    public enum MsgClientDoneSerializer implements Serializer<MsgClientDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgClientDone msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgClientDone deserialize(byte[] bytes) {
            DataItem di = CborSerializationUtil.deserializeOne(bytes);
            int key = ((UnsignedInteger) ((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 3) return new MsgClientDone();
            throw new CborRuntimeException("Invalid MsgClientDone label: " + key);
        }
    }
}
