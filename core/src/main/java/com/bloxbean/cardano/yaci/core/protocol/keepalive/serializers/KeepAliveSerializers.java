package com.bloxbean.cardano.yaci.core.protocol.keepalive.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class KeepAliveSerializers {

    public enum MsgKeepAliveSerializer implements Serializer<MsgKeepAlive> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgKeepAlive message) {
            Array array = new Array();
            array.add(new UnsignedInteger(0));
            array.add(new UnsignedInteger(message.getCookie()));

            if (log.isDebugEnabled()) {
                log.debug("MsgKeepAlive (serialized) : " + HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            }

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgKeepAlive deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array) di).getDataItems();
            int key = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (key != 0)
                throw new IllegalStateException("Invalid key. Expected : " + 0 + " Found: " + key);

            int word = ((UnsignedInteger) dataItemList.get(1)).getValue().intValue();

            return new MsgKeepAlive(word);
        }
    }

    public enum MsgKeepAliveResponseSerializer implements Serializer<MsgKeepAliveResponse> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgKeepAliveResponse message) {
            Array array = new Array();
            array.add(new UnsignedInteger(1));
            array.add(new UnsignedInteger(message.getCookie()));

            if (log.isDebugEnabled()) {
                log.debug("MsgKeepAliveResponse (serialized) : " + HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));
            }

            return CborSerializationUtil.serialize(array);
        }

        @Override
        public MsgKeepAliveResponse deserializeDI(DataItem di) {
            List<DataItem> dataItemList = ((Array) di).getDataItems();
            int key = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
            if (key != 1)
                throw new IllegalStateException("Invalid key. Expected : " + 0 + " Found: " + key);

            int word = ((UnsignedInteger) dataItemList.get(1)).getValue().intValue();

            return new MsgKeepAliveResponse(word);
        }
    }

    public enum MsgDoneSerializer implements Serializer<MsgDone> {
        INSTANCE;

        @Override
        public byte[] serialize(MsgDone object) {
            Array array = new Array();
            array.add(new UnsignedInteger(2));

            if (log.isDebugEnabled())
                log.debug("MsgDone (serialized): {}", HexUtil.encodeHexString(CborSerializationUtil.serialize(array)));

            return CborSerializationUtil.serialize(array);
        }
    }
}
