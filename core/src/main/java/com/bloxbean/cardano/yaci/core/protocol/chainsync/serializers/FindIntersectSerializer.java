package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.FindIntersect;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum FindIntersectSerializer implements Serializer<FindIntersect> {
    INSTANCE();

    @Override
    public byte[] serialize(FindIntersect intersect) {
        Array array = new Array();
        array.add(new UnsignedInteger(4));
        Array pointsArr = new Array();
        if (intersect.getPoints() != null) {
            for (Point point : intersect.getPoints()) {
                pointsArr.add(PointSerializer.INSTANCE.serializeDI(point));
            }
        }
        array.add(pointsArr);

        byte[] bytes = CborSerializationUtil.serialize(array, false);
        return bytes;
    }

    @Override
    public FindIntersect deserializeDI(DataItem di) {
        Array array = (Array) di;
        DataItem pointsDataItem = array.getDataItems().get(1);
        Array pointsArray = (Array) pointsDataItem;
        var pointsDIList = pointsArray.getDataItems();

        Point[] points = new Point[pointsDIList.size()];
        for (int i=0; i < points.length; i++) {
            points[i] = PointSerializer.INSTANCE.deserializeDI(pointsDIList.get(i));
        }
        return new FindIntersect(points);

    }
}
