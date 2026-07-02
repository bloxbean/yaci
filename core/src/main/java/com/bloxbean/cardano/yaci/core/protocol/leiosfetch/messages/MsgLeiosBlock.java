package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import java.util.Objects;

public class MsgLeiosBlock implements Message {
    private final LeiosRawCbor endorserBlock;

    public MsgLeiosBlock(LeiosRawCbor endorserBlock) {
        this.endorserBlock = Objects.requireNonNull(endorserBlock, "endorserBlock");
    }

    public LeiosRawCbor getEndorserBlock() {
        return endorserBlock;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockSerializer.INSTANCE.serialize(this);
    }
}
