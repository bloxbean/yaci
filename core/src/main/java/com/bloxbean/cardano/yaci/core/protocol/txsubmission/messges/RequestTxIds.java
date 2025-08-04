package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RequestTxIds implements Message {
    private boolean blocking;
    private short ackTxIds;
    private short reqTxIds;

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.RequestTxIdsSerializer.INSTANCE.serialize(this);
    }
}
