package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum MsgDoneSerializer implements Serializer<MsgDone> {
    INSTANCE;

    @Override
    public byte[] serialize(MsgDone msgDone) {
        Array array = new Array();
        array.add(new UnsignedInteger(7));

        if (log.isDebugEnabled())
            log.debug("MsgDone (serialized): {}", HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

        return CborSerializationUtil.serialize(array);
    }
}
