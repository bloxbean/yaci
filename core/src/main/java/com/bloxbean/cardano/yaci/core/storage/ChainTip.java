package com.bloxbean.cardano.yaci.core.storage;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ChainTip {
    private long slot;
    @JsonIgnore
    private byte[] blockHash;
    private long blockNumber;

    @JsonGetter("blockHash")
    public String getBlockHashHex() {
        return blockHash != null ? HexUtil.encodeHexString(blockHash) : null;
    }

    @Override
    public String toString() {
        return "ChainTip{" +
                "slot=" + slot +
                ", blockHash=" + (blockHash != null? HexUtil.encodeHexString(blockHash): "") +
                ", blockNumber=" + blockNumber +
                '}';
    }
}
