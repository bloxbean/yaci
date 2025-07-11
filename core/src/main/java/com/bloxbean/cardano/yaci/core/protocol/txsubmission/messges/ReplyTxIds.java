package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Getter
@RequiredArgsConstructor
public class ReplyTxIds implements Message {

    private final Era era;

    private Map<String, Integer> txIdAndSizeMap;

    public ReplyTxIds() {
        this(Era.Babbage);
    }

    public ReplyTxIds(Era era, Map<String, Integer> txIdAndSizeMap) {
        this.era = era;
        this.txIdAndSizeMap = txIdAndSizeMap;
    }

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
