package com.bloxbean.cardano.yaci.core.protocol.localtx.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgSubmitTx;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public class LocalTxSubmissionSerializers {
    public enum MsgSubmitTxSerializer implements Serializer<MsgSubmitTx> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgSubmitTx message) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(new ByteString(message.getTxnBytes()));

            return CborSerializationUtil.serialize(array);
        }

        //TODO -- deserialize is not used in client. So not implemented now
    }

    public enum MsgAcceptTxSerializer implements Serializer<MsgAcceptTx> {
        INSTANCE;

        @Override
        public MsgAcceptTx deserializeDI(DataItem di) {
            return new MsgAcceptTx();
        }
    }

    public enum MsgRejectTxSerializer implements Serializer<MsgRejectTx> {
        INSTANCE;

        @Override
        public MsgRejectTx deserializeDI(DataItem di) {
            return new MsgRejectTx();
        }
    }

    public enum MsgDone implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));

            return CborSerializationUtil.serialize(array);
        }
    }
}
