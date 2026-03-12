package com.bloxbean.cardano.yaci.node.api.ledger;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.Builder;
import lombok.Getter;

/**
 * Tip information for a topic in the app ledger.
 */
@Getter
@Builder
public class AppLedgerTip {
    private final String topicId;
    private final long blockNumber;
    private final byte[] blockHash;
    private final long timestamp;
    private final long totalMessages;

    public String blockHashHex() {
        return blockHash != null ? HexUtil.encodeHexString(blockHash) : null;
    }
}
