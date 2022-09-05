package com.bloxbean.cardano.yaci.core.protocol.localstate;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LocalStateQueryStateBase extends State {
    Logger log = LoggerFactory.getLogger(LocalStateQueryStateBase.class);

    @Override
    default Message handleInbound(byte[] bytes) {
        try {
            DataItem di = CborSerializationUtil.deserialize(bytes);
            Array array = (Array) di;
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            switch (id) {
                case 0:
                case 8:
                    return MsgAcquireSerializer.INSTANCE.deserialize(bytes);
                case 1:
                    return MsgAcquiredSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    return MsgFailureSerializer.INSTANCE.deserialize(bytes);
                case 3:
                    return MsgQuerySerializer.INSTANCE.deserialize(bytes);
                case 4:
                    return MsgResultSerializer.INSTANCE.deserialize(bytes);
                case 5:
                    return MsgReleaseSerializer.INSTANCE.deserialize(bytes);
                case 6:
                case 9:
                    return MsgReAcquireSerializer.INSTANCE.deserialize(bytes);
                case 7:
                    return MsgDoneSerializer.INSTANCE.deserialize(bytes);
                default:
                    throw new RuntimeException(String.format("Invalid msg id: %d", id));
            }
        } catch (Exception e) {
            log.error("Parsing error ", e);
            log.error("LocalState data");
            log.error(HexUtil.encodeHexString(bytes));
            return null;
        }
    }
}
