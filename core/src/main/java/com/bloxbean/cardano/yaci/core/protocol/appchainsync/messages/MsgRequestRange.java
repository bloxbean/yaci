package com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.serializers.AppChainSyncSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Client requests finalized app blocks [fromHeight..toHeight] for a chain. CBOR: [0, chainId, from, to] */
@Getter
@AllArgsConstructor
public class MsgRequestRange implements Message {
    private final String chainId;
    private final long fromHeight;
    private final long toHeight;

    @Override
    public byte[] serialize() {
        return AppChainSyncSerializers.MsgRequestRangeSerializer.INSTANCE.serialize(this);
    }
}
