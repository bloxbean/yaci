package com.bloxbean.cardano.yaci.core.protocol.appmsg.model;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * ID + size tuple for gossip protocol (mirrors TxId pattern).
 */
@Getter
@AllArgsConstructor
public class AppMessageId {
    private final byte[] messageId;
    private final int size;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppMessageId that = (AppMessageId) o;
        return Arrays.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(messageId);
    }

    @Override
    public String toString() {
        return HexUtil.encodeHexString(messageId);
    }
}
