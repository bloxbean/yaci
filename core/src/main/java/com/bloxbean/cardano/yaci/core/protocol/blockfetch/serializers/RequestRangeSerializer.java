package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.RequestRange;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.PointSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum RequestRangeSerializer implements Serializer<RequestRange> {
    INSTANCE;

    @Override
    public byte[] serialize(RequestRange range) {
        Array array = new Array();
        array.add(new UnsignedInteger(0));
        array.add(PointSerializer.INSTANCE.serializeDI(range.getFrom()));
        array.add(PointSerializer.INSTANCE.serializeDI(range.getTo()));

        byte[] bytes = CborSerializationUtil.serialize(array, false);
        return bytes;
    }
}
