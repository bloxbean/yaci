package com.bloxbean.cardano.yaci.core.storage;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ChainTip {
    private long slot;
    private byte[] blockHash;
    private long blockNumber;

    @Override
    public String toString() {
        return "ChainTip{" +
                "slot=" + slot +
                ", blockHash=" + (blockHash != null? HexUtil.encodeHexString(blockHash): "") +
                ", blockNumber=" + blockNumber +
                '}';
    }
}
