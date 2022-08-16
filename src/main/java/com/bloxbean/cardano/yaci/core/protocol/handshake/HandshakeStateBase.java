package com.bloxbean.cardano.yaci.core.protocol.handshake;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.serializers.HandshakeSerializers.*;

public interface HandshakeStateBase extends State {

    default Message handleInbound(byte[] bytes) {
        Array array = (Array) CborSerializationUtil.deserialize(bytes);
        int id = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        switch (id) {
            case 0:
                return ProposedVersionSerializer.INSTANCE.deserialize(bytes); //Not used. Empty implementation as we are the sender here.
            case 1:
                return AcceptVersionSerializer.INSTANCE.deserialize(bytes);
            case 2:
                DataItem refuseDI = array.getDataItems().get(1);
                Message message = ReasonVersionMismatchSerializer.INSTANCE.deserializeDI(refuseDI);

                if (message == null) {
                    message = ReasonHandshakeDecodeErrorSerializer.INSTANCE.deserializeDI(refuseDI);
                }

                if (message == null) {
                    message = ReasonRefusedSerializer.INSTANCE.deserializeDI(refuseDI);
                }

                return message;
            default:
                throw new RuntimeException(String.format("Invalid msg id: %d", id));
        }
    }
}
