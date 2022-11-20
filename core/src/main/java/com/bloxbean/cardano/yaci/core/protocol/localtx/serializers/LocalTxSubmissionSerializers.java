package com.bloxbean.cardano.yaci.core.protocol.localtx.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgSubmitTx;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalTxSubmissionSerializers {
    public enum MsgSubmitTxSerializer implements Serializer<MsgSubmitTx> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgSubmitTx message) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));

            Array txnArr = new Array();
            txnArr.add(new UnsignedInteger(message.getTxBodyType().getValue())); //txn body type

            ByteString txBs = new ByteString(message.getTxnBytes());
            txBs.setTag(24);
            txnArr.add(txBs);

            array.add(txnArr);

            if (log.isDebugEnabled()) {
                log.debug("MsgSubmitTx (serialized) : " + HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            }

            return CborSerializationUtil.serialize(array);
        }

        //TODO -- deserialize is not used in client. So not implemented now
    }

    public enum MsgAcceptTxSerializer implements Serializer<MsgAcceptTx> {
        INSTANCE;

        @Override
        public MsgAcceptTx deserializeDI(DataItem di) {
            if (log.isDebugEnabled())
                log.debug("MsgAcceptTx (serialized): {}", HexUtil.encodeHexString(CborSerializationUtil.serialize(di)));
            return new MsgAcceptTx();
        }
    }

    public enum MsgRejectTxSerializer implements Serializer<MsgRejectTx> {
        INSTANCE;

        @Override
        public MsgRejectTx deserializeDI(DataItem di) {
            String reasonCbor = HexUtil.encodeHexString(CborSerializationUtil.serialize(di));
            if (log.isDebugEnabled())
                log.debug("MsgRejectTx (serialized) : {}", reasonCbor);

            return new MsgRejectTx(reasonCbor);
        }
    }

    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(3));

            if (log.isDebugEnabled())
                log.debug("MsgDone (serialized): {}", HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }
}
