package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.LocalTxSubmissionSerializers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MsgSubmitTx implements Message {
    private TxBodyType txBodyType;
    private byte[] txnBytes;

    @Override
    public byte[] serialize() {
        return LocalTxSubmissionSerializers.MsgSubmitTxSerializer.INSTANCE.serialize(this);
    }
}
