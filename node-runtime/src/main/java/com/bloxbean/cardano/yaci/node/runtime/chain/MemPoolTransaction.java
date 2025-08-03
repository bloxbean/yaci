package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;

record MemPoolTransaction(long seqId, byte[] txHash, byte[] txBytes, TxBodyType txBodyType) {
}
