package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.serializers.KeepAliveSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface KeepAliveStateBase extends State {
    Logger log = LoggerFactory.getLogger(KeepAliveStateBase.class);

    default Message handleInbound(byte[] bytes) {
        try {
            Array array = (Array) CborSerializationUtil.deserializeOne(bytes);
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            var bytesHex = HexUtil.encodeHexString(bytes);
            switch (id) {
                case 0:
                    log.info("0 - bytesHex: {}", bytesHex);
                    return KeepAliveSerializers.MsgKeepAliveSerializer.INSTANCE.deserialize(bytes); //Not used. Empty implementation as we are the sender here.
                case 1:
                    log.info("1 - bytesHex: {}", bytesHex);
                    return KeepAliveSerializers.MsgKeepAliveResponseSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    log.info("2 - bytesHex: {}", bytesHex);
                    return KeepAliveSerializers.MsgDoneSerializer.INSTANCE.deserialize(bytes);
                default:
                    throw new RuntimeException(String.format("Invalid msg id: %d", id));
            }
        } catch (Exception e) {
            log.error("Parsing error ", e);
            log.error("Handshake data");
            log.error(HexUtil.encodeHexString(bytes));
            return null;
        }
    }
}
