package com.bloxbean.cardano.yaci.core.protocol.handshake;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.serializers.HandshakeSerializers.*;

public interface HandshakeStateBase extends State {
    Logger log = LoggerFactory.getLogger(HandshakeStateBase.class);

    default Message handleInbound(byte[] bytes) {
        try {
            Array array = (Array) CborSerializationUtil.deserializeOne(bytes);
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            String bytesHex = null;
            switch (id) {
                case 0:
                    // Paragraph 3.6.2 State machine
                    bytesHex = HexUtil.encodeHexString(bytes);
                    log.info("0 - bytesHex: {}", bytesHex);
                    return ProposedVersionSerializer.INSTANCE.deserialize(bytes); //Not used. Empty implementation as we are the sender here.
                case 1:
                    bytesHex = HexUtil.encodeHexString(bytes);
                    log.info("1 - bytesHex: {}", bytesHex);
                    return AcceptVersionSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    log.info("2 - bytesHex: {}", bytesHex);
                    DataItem refuseDI = array.getDataItems().get(1);
                    Message message = ReasonVersionMismatchSerializer.INSTANCE.deserializeDI(refuseDI);

                    if (message == null) {
                        message = ReasonHandshakeDecodeErrorSerializer.INSTANCE.deserializeDI(refuseDI);
                    }

                    if (message == null) {
                        message = ReasonRefusedSerializer.INSTANCE.deserializeDI(refuseDI);
                    }

                    return message;
                case 3:
                    log.info("3 - bytesHex: {}", bytesHex);
                    DataItem versionTableDI = array.getDataItems().get(1);
                    return QueryReplySerializer.INSTANCE.deserializeDI(versionTableDI);
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
