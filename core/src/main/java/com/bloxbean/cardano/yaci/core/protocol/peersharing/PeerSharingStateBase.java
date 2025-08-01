package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.serializers.PeerSharingSerializers;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface PeerSharingStateBase extends State {
    Logger log = LoggerFactory.getLogger(PeerSharingStateBase.class);

    default Message handleInbound(byte[] bytes) {
        try {
            Array array = (Array) CborSerializationUtil.deserializeOne(bytes);
            int id = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
            
            if (log.isTraceEnabled()) {
                log.trace("Processing peer sharing message with ID: {}", id);
                log.trace("Raw bytes: {}", HexUtil.encodeHexString(bytes));
            }
            
            switch (id) {
                case 0:
                    // MsgShareRequest - should not be received by client
                    return PeerSharingSerializers.MsgShareRequestSerializer.INSTANCE.deserialize(bytes);
                case 1:
                    // MsgSharePeers - main response message
                    return PeerSharingSerializers.MsgSharePeersSerializer.INSTANCE.deserialize(bytes);
                case 2:
                    // MsgDone - protocol termination
                    return PeerSharingSerializers.MsgDoneSerializer.INSTANCE.deserialize(bytes);
                default:
                    log.error("Invalid peer sharing message ID: {}", id);
                    throw new RuntimeException(String.format("Invalid peer sharing message ID: %d", id));
            }
        } catch (Exception e) {
            log.error("Error parsing peer sharing message", e);
            if (log.isDebugEnabled()) {
                log.debug("Raw bytes: {}", HexUtil.encodeHexString(bytes));
            }
            return null;
        }
    }

    PeerSharingState nextState(com.bloxbean.cardano.yaci.core.protocol.Message message);
}