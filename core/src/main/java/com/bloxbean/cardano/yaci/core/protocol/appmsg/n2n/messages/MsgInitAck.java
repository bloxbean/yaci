package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Sent by the server in response to {@link MsgInit}, carrying the server's own
 * chain-ids. Both sides then restrict the session to the intersection: the client
 * only offers messages for shared chains, the server rejects anything else.
 * CBOR: [6, [chainId(tstr), ...]]
 */
@Getter
public class MsgInitAck implements Message {
    private final List<String> chainIds;

    public MsgInitAck() {
        this(Collections.emptyList());
    }

    public MsgInitAck(List<String> chainIds) {
        this.chainIds = chainIds != null ? List.copyOf(chainIds) : Collections.emptyList();
    }

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgInitAckSerializer.INSTANCE.serialize(this);
    }
}
