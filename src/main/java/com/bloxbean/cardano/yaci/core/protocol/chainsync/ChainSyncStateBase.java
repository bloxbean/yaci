package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.AwaitReply;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.ChainSyncMsgDone;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.IntersectFoundSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RollForwardSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RollbackwardSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public interface ChainSyncStateBase extends State {

    default Message handleInbound(byte[] bytes) {
        Array array = (Array) CborSerializationUtil.deserialize(bytes);
        int id = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        switch (id) {
            case 1:
                return new AwaitReply();
            case 2:
                return RollForwardSerializer.INSTANCE.deserialize(bytes);
            case 3:
                return RollbackwardSerializer.INSTANCE.deserialize(bytes);
            case 5:
                return IntersectFoundSerializer.INSTANCE.deserialize(bytes);
            case 6:
                return IntersectFoundSerializer.INSTANCE.deserialize(bytes);
            case 7:
                return new ChainSyncMsgDone();
            default:
                throw new RuntimeException(String.format("Invalid msg id: %d", id));
        }
    }
}
