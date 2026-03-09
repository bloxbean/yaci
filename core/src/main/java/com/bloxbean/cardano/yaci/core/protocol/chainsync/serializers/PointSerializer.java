package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

public enum PointSerializer implements Serializer<Point> {
    INSTANCE();

    @Override
    public DataItem serializeDI(Point point) {
        Array array = new Array();
        if (point != Point.ORIGIN) {
            array.add(new UnsignedInteger(point.getSlot()));
            array.add(new ByteString(HexUtil.decodeHexString(point.getHash())));
        }

        return array;
    }

    @Override
    public Point deserializeDI(DataItem di) {
        // Handle Special values (like null/undefined) which represent Point.ORIGIN
        if (di instanceof Special) {
            return Point.ORIGIN;
        }

        Array array = (Array) di;
        if (array.getDataItems().isEmpty())
            return Point.ORIGIN;

        long slot = ((UnsignedInteger)array.getDataItems().get(0)).getValue().longValue();
        String hash = HexUtil.encodeHexString(((ByteString)array.getDataItems().get(1)).getBytes());

        return new Point(slot, hash);
    }
}
