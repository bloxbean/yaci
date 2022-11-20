package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReplyTxs implements Message {
    private List<byte[]> txns;

    public void addTx(byte[] tx) {
        if (txns == null)
            txns = new ArrayList<>();

        txns.add(tx);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.ReplyTxsSerializer.INSTANCE.serialize(this);
    }
}
