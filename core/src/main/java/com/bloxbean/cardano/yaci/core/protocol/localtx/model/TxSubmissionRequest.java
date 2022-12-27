package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class TxSubmissionRequest {
    private TxBodyType txBodyType;
    private byte[] txnBytes;
    private String txHash;

    public TxSubmissionRequest(byte[] txnBytes) {
       this(TxBodyType.BABBAGE, txnBytes);
    }

    public TxSubmissionRequest(TxBodyType txBodyType, byte[] txnBytes) {
        if (txnBytes == null)
            throw new RuntimeException("TxBytes can't be null");

        this.txBodyType = txBodyType;
        this.txnBytes = txnBytes;
        this.txHash = TransactionUtil.getTxHash(txnBytes);
    }

    @Override
    public String toString() {
        return "TxSubmissionRequest{" +
                "txBodyType=" + txBodyType +
                ", txnBytes=" + (txnBytes != null? HexUtil.encodeHexString(txnBytes) : "") +
                ", txnHash=" + txHash +
                '}';
    }
}
