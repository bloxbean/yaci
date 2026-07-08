package com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.serializers.AppChainSyncSerializers;

/** Client terminates the protocol. CBOR: [3] */
public class MsgDone implements Message {
    @Override
    public byte[] serialize() {
        return AppChainSyncSerializers.MsgDoneSerializer.INSTANCE.serialize(this);
    }
}
