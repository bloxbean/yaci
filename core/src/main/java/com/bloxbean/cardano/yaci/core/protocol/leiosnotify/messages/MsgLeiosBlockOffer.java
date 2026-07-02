package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

import java.util.Objects;

public class MsgLeiosBlockOffer implements Message {
    public static final long MAX_EB_SIZE = 0xFFFF_FFFFL;

    private final LeiosPoint point;
    private final long ebSize;

    public MsgLeiosBlockOffer(LeiosPoint point, long ebSize) {
        if (ebSize < 0 || ebSize > MAX_EB_SIZE) {
            throw new IllegalArgumentException("ebSize must fit in word32");
        }
        this.point = Objects.requireNonNull(point, "point");
        this.ebSize = ebSize;
    }

    public LeiosPoint getPoint() {
        return point;
    }

    public long getEbSize() {
        return ebSize;
    }

    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgLeiosBlockOfferSerializer.INSTANCE.serialize(this);
    }
}
