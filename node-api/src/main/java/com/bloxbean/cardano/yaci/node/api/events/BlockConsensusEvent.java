package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.VetoableEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Published before a received block is accepted into the chain state.
 * Default behavior: accept all blocks (no built-in consensus logic).
 * Plugins can implement consensus rules by listening and calling {@link #reject(String, String)}.
 * <p>
 * Thread safety: Same as {@link TransactionValidateEvent} — synchronous dispatch only.
 */
public final class BlockConsensusEvent implements VetoableEvent {

    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final byte[] blockCbor;
    private final List<Rejection> rejections = new ArrayList<>();

    /**
     * @param slot        block slot number
     * @param blockNumber block number
     * @param blockHash   hex-encoded block hash
     * @param blockCbor   raw block CBOR bytes (may be null if not available)
     */
    public BlockConsensusEvent(long slot, long blockNumber, String blockHash, byte[] blockCbor) {
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.blockCbor = blockCbor;
    }

    public long slot() { return slot; }
    public long blockNumber() { return blockNumber; }
    public String blockHash() { return blockHash; }
    public byte[] blockCbor() { return blockCbor; }

    @Override
    public void reject(String source, String reason) {
        rejections.add(new Rejection(source, reason));
    }

    @Override
    public boolean isRejected() {
        return !rejections.isEmpty();
    }

    @Override
    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }
}
