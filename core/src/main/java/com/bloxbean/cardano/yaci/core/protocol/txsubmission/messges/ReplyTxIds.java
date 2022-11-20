package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReplyTxIds implements Message {
    private Map<String, Integer> txIdAndSizeMap;

    public void addTxId(String id, int size) {
        if (txIdAndSizeMap == null)
            txIdAndSizeMap = new HashMap<>();
        txIdAndSizeMap.put(id, size);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.ReplyTxIdsSerializer.INSTANCE.serialize(this);
    }
}
