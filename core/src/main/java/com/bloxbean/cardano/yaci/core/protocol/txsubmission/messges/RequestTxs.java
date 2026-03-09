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
public class RequestTxs implements Message {
    private List<TxId> txIds;

    public void addTxnId(TxId txId) {
        if (txIds == null)
            txIds = new ArrayList<>();

        txIds.add(txId);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.RequestTxsSerializer.INSTANCE.serialize(this);
    }
}
