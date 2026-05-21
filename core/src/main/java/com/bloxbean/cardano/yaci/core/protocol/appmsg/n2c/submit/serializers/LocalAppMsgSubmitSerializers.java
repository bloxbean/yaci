package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

public class LocalAppMsgSubmitSerializers {

    // Tag 0: MsgSubmitMessage [0, appMessage]
    public enum MsgSubmitMessageSerializer implements Serializer<MsgSubmitMessage> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgSubmitMessage msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(AppMsgSubmissionSerializers.serializeAppMessage(msg.getAppMessage()));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgSubmitMessage deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 0)
                throw new CborRuntimeException("Invalid MsgSubmitMessage label: " + label);
            var appMessage = AppMsgSubmissionSerializers.deserializeAppMessage((Array) items.get(1));
            return new MsgSubmitMessage(appMessage);
        }
    }

    // Tag 1: MsgAcceptMessage [1]
    public enum MsgAcceptMessageSerializer implements Serializer<MsgAcceptMessage> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgAcceptMessage msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgAcceptMessage deserializeDI(DataItem di) {
            Array array = (Array) di;
            int label = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            if (label == 1) return new MsgAcceptMessage();
            throw new CborRuntimeException("Invalid MsgAcceptMessage label: " + label);
        }
    }

    // Tag 2: MsgRejectMessage [2, reasonCode, detail]
    public enum MsgRejectMessageSerializer implements Serializer<MsgRejectMessage> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgRejectMessage msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));
            array.add(new UnsignedInteger(msg.getReason().getValue()));
            array.add(new UnicodeString(msg.getDetail() != null ? msg.getDetail() : ""));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgRejectMessage deserializeDI(DataItem di) {
            Array array = (Array) di;
            List<DataItem> items = array.getDataItems();
            int label = ((UnsignedInteger) items.get(0)).getValue().intValue();
            if (label != 2)
                throw new CborRuntimeException("Invalid MsgRejectMessage label: " + label);
            int reasonCode = ((UnsignedInteger) items.get(1)).getValue().intValue();
            String detail = ((UnicodeString) items.get(2)).getString();
            return new MsgRejectMessage(RejectReason.fromValue(reasonCode), detail);
        }
    }

    // Tag 3: MsgDone [3]
    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone msg) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));
            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgDone deserialize(byte[] bytes) {
            DataItem di = CborSerializationUtil.deserializeOne(bytes);
            int key = ((UnsignedInteger) ((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 3) return new MsgDone();
            throw new CborRuntimeException("Invalid MsgDone label: " + key);
        }
    }
}
