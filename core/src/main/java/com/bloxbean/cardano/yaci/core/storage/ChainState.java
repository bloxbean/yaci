package com.bloxbean.cardano.yaci.core.storage;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

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

    /**
     * Get slot number for a given block number.
     * Returns null if no block exists with the given number.
     */
    Long getSlotByBlockNumber(Long blockNumber);

    void rollbackTo(Long slot);

    ChainTip getTip();
    ChainTip getHeaderTip();

    /**
     * Determine the era of a stored block by reading the CBOR era tag.
     * The block CBOR is always [eraTag, blockData] where eraTag is 0=Byron, 1=Shelley, etc.
     *
     * @param blockNumber the block number to check
     * @return the era of the block, or null if not found or era cannot be determined
     */
    default Era getBlockEra(long blockNumber) {
        byte[] blockBytes = getBlockByNumber(blockNumber);
        if (blockBytes == null) return null;
        try {
            DataItem di = CborSerializationUtil.deserializeOne(blockBytes);
            int eraValue = ((UnsignedInteger) ((Array) di).getDataItems().get(0)).getValue().intValue();
            return EraUtil.getEra(eraValue);
        } catch (Exception e) {
            return null;
        }
    }
}
