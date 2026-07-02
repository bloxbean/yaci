package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

import java.util.Objects;

public class MsgLeiosBlockTxs implements Message {
    private final LeiosPoint point;
    private final LeiosTxBitmap bitmap;
    private final LeiosRawCbor txList;

    public MsgLeiosBlockTxs(LeiosPoint point, LeiosTxBitmap bitmap, LeiosRawCbor txList) {
        this.point = Objects.requireNonNull(point, "point");
        this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
        this.txList = Objects.requireNonNull(txList, "txList");
    }

    public LeiosPoint getPoint() {
        return point;
    }

    public LeiosTxBitmap getBitmap() {
        return bitmap;
    }

    public LeiosRawCbor getTxList() {
        return txList;
    }

    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgLeiosBlockTxsSerializer.INSTANCE.serialize(this);
    }
}
