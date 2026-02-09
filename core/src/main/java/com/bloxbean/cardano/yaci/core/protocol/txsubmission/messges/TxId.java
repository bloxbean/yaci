package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TxId {
    private Era era;
    private byte[] txId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TxId txId1 = (TxId) o;

        if (era != txId1.era) return false;
        return java.util.Arrays.equals(txId, txId1.txId);
    }

    @Override
    public int hashCode() {
        int result = era != null ? era.hashCode() : 0;
        result = 31 * result + java.util.Arrays.hashCode(txId);
        return result;
    }

    @Override
    public String toString() {
        return HexUtil.encodeHexString(txId);
    }
}
