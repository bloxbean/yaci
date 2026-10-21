package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import java.util.Objects;

/**
 * Reply to {@link MsgLeiosBlockTxsRequest}: {@code [4, [* #6.24(bytes .cbor block_transaction)]]}.
 * It does not echo the requested point or bitmap.
 */
public class MsgLeiosBlockTxs implements Message {
    private final LeiosRawCbor txList;

    public MsgLeiosBlockTxs(LeiosRawCbor txList) {
        this.txList = Objects.requireNonNull(txList, "txList");
    }

    public LeiosRawCbor getTxList() {
        return txList;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockTxsSerializer.INSTANCE.serialize(this);
    }
}
