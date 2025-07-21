package com.bloxbean.cardano.yaci.core.storage;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

import java.util.List;

public interface ChainState {

    void storeBlock(byte[] blockHash, long blockNumber, long slot, byte[] block);

    byte[] getBlock(byte[] blockHash);

    void storeBlockHeader(byte[] blockHash, byte[] blockHeader);

    byte[] getBlockHeader(byte[] blockHash);

    byte[] getBlockByNumber(long blockNumber);
    
    /**
     * Get block header by block number
     */
    byte[] getBlockHeaderByNumber(long blockNumber);
    
    /**
     * Find the next block after the given point
     */
    Point findNextBlock(Point currentPoint);
    
    /**
     * Find blocks in a range between two points
     */
    List<Point> findBlocksInRange(Point from, Point to);
    
    /**
     * Check if a point exists in the chain
     */
    boolean hasPoint(Point point);
    
    /**
     * Get block number for a given slot
     */
    Long getBlockNumberBySlot(long slot);

    void rollbackTo(long slot);

    ChainTip getTip();
}
