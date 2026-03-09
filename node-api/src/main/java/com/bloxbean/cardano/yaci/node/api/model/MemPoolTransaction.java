package com.bloxbean.cardano.yaci.node.api.model;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;

public record MemPoolTransaction(long seqId, byte[] txHash, byte[] txBytes, TxBodyType txBodyType, long insertedAt) {

    public MemPoolTransaction(long seqId, byte[] txHash, byte[] txBytes, TxBodyType txBodyType) {
        this(seqId, txHash, txBytes, txBodyType, System.currentTimeMillis());
    }
}
