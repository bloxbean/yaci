package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import java.util.Objects;

public class MsgLeiosBlockRequest implements Message {
    private final LeiosPoint point;

    public MsgLeiosBlockRequest(LeiosPoint point) {
        this.point = Objects.requireNonNull(point, "point");
    }

    public LeiosPoint getPoint() {
        return point;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockRequestSerializer.INSTANCE.serialize(this);
    }
}
