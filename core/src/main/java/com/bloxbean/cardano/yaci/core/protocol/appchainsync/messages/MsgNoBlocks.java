package com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.serializers.AppChainSyncSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Server reply when it has nothing in range; carries its tip height. CBOR: [2, tip] */
@Getter
@AllArgsConstructor
public class MsgNoBlocks implements Message {
    private final long tipHeight;

    @Override
    public byte[] serialize() {
        return AppChainSyncSerializers.MsgNoBlocksSerializer.INSTANCE.serialize(this);
    }
}
