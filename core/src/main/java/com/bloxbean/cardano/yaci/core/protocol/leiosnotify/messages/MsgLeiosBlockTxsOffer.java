package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

import java.util.Objects;

public class MsgLeiosBlockTxsOffer implements Message {
    private final LeiosPoint point;

    public MsgLeiosBlockTxsOffer(LeiosPoint point) {
        this.point = Objects.requireNonNull(point, "point");
    }

    public LeiosPoint getPoint() {
        return point;
    }

    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgLeiosBlockTxsOfferSerializer.INSTANCE.serialize(this);
    }
}
