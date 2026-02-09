package com.bloxbean.cardano.yaci.core.storage;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

import java.util.List;

public interface ChainState {

    void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block);

    byte[] getBlock(byte[] blockHash);

    /**
     * Lightweight existence check for a stored block body by hash.
     * Implementations should avoid loading the full value into memory where possible.
     */
    boolean hasBlock(byte[] blockHash);

    void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader);

    byte[] getBlockHeader(byte[] blockHash);

    byte[] getBlockByNumber(Long blockNumber);

    /**
     * Get block header by block number
     */
    byte[] getBlockHeaderByNumber(Long blockNumber);

    /**
     * Find the next block after the given point
     */
    Point findNextBlock(Point currentPoint);

    /**
     * Find the next block header after the given point
     * This is useful for pipeline mode where headers are ahead of bodies
     */
    Point findNextBlockHeader(Point currentPoint);

    /**
     * Find blocks in a range between two points
     */
    List<Point> findBlocksInRange(Point from, Point to);


    Point findLastPointAfterNBlocks(Point from, long batchSize);

    /**
     * Check if a point exists in the chain
     */
    boolean hasPoint(Point point);

    /**
     * Get the first block in the chain
     */
    Point getFirstBlock();

    /**
     * Get block number for a given slot
     */
    Long getBlockNumberBySlot(Long slot);

    void rollbackTo(Long slot);

    ChainTip getTip();
    ChainTip getHeaderTip();
}
