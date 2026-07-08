package com.bloxbean.cardano.yaci.core.protocol.appchainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.serializers.AppChainSyncSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** Server reply: raw app-block CBOR blobs + the server's tip height. CBOR: [1, [bstr...], tip] */
@Getter
@AllArgsConstructor
public class MsgBlocks implements Message {
    private final List<byte[]> blocks;
    private final long tipHeight;

    @Override
    public byte[] serialize() {
        return AppChainSyncSerializers.MsgBlocksSerializer.INSTANCE.serialize(this);
    }
}
