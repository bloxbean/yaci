package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class ReplyTxIds implements Message {
    private Map<TxId, Integer> txIdAndSizeMap;

    public ReplyTxIds() {
        this.txIdAndSizeMap = new HashMap<>();
    }

    public ReplyTxIds(Map<TxId, Integer> txIdAndSizeMap) {
        this.txIdAndSizeMap = txIdAndSizeMap;
    }

    public void addTxId(Era era, String id, int size) {
        if (txIdAndSizeMap == null)
            txIdAndSizeMap = new HashMap<>();

        var txId = new TxId(era, HexUtil.decodeHexString(id));
        txIdAndSizeMap.put(txId, size);
    }

    public void addTxId(TxId txId, int size) {
        if (txIdAndSizeMap == null)
            txIdAndSizeMap = new HashMap<>();

        txIdAndSizeMap.put(txId, size);
    }

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.ReplyTxIdsSerializer.INSTANCE.serialize(this);
    }
}
