package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import java.util.Objects;

public class MsgLeiosBlockTxsRequest implements Message {
    private final LeiosPoint point;
    private final LeiosTxBitmap bitmap;

    public MsgLeiosBlockTxsRequest(LeiosPoint point, LeiosTxBitmap bitmap) {
        this.point = Objects.requireNonNull(point, "point");
        this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
    }

    public LeiosPoint getPoint() {
        return point;
    }

    public LeiosTxBitmap getBitmap() {
        return bitmap;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockTxsRequestSerializer.INSTANCE.serialize(this);
    }
}
