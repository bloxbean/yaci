package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
public class InMemoryChainState implements ChainState {
    private Map<byte[], byte[]> blockStore = new ConcurrentHashMap<>();
    private Map<byte[], byte[]> blockHeaderStore = new ConcurrentHashMap<>();
    private Map<Long, byte[]> blockHashByNumber = new ConcurrentHashMap<>();
    private ConcurrentSkipListMap<Long, Long> blockNumberBySlot = new ConcurrentSkipListMap<>();

    private ChainTip tip;
    private ChainTip headerTip;

    @Override
    public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {
        blockStore.put(blockHash, block);
        blockHeaderStore.put(blockHash, block);
        blockHashByNumber.put(blockNumber, blockHash);
        blockNumberBySlot.put(slot, blockNumber);
        tip = new ChainTip(slot, blockHash, blockNumber);
    }

    @Override
    public byte[] getBlock(byte[] blockHash) {
        return blockStore.get(blockHash);
    }

    @Override
    public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        blockHeaderStore.put(blockHash, blockHeader);
        headerTip = new ChainTip(slot, blockHash, blockNumber);
    }

    @Override
    public byte[] getBlockHeader(byte[] blockHash) {
        return blockHeaderStore.get(blockHash);
    }

    @Override
    public byte[] getBlockByNumber(Long number) {
        byte[] blockHash = blockHashByNumber.get(number);
        if (blockHash != null) {
            return blockStore.get(blockHash);
        }
        return null;
    }

    @Override
    public void rollbackTo(Long slot) {
        // Get all block numbers greater than the provided slot
        blockNumberBySlot.tailMap(slot, false).forEach((blockSlot, blockNumber) -> {
            byte[] blockHash = blockHashByNumber.get(blockNumber);
            if (blockHash != null) {
                blockStore.remove(blockHash);
                blockHeaderStore.remove(blockHash);
                blockHashByNumber.remove(blockNumber);
            }
        });

        // Remove the entries from blockNumberBySlot where slots are greater than the provided slot
        blockNumberBySlot.tailMap(slot, false).clear();
    }

    @Override
    public ChainTip getTip() {
        return tip;
    }

    @Override
    public ChainTip getHeaderTip() {
        return headerTip;
    }

    @Override
    public byte[] getBlockHeaderByNumber(Long blockNumber) {
        byte[] blockHash = blockHashByNumber.get(blockNumber);
        if (blockHash != null) {
            return blockHeaderStore.get(blockHash);
        }
        return null;
    }

    @Override
    public Point findNextBlockHeader(Point currentPoint) {
        // For in-memory implementation, headers and blocks are stored together
        // So this behaves the same as findNextBlock but checks against header tip
        if (currentPoint == null) {
            return null;
        }
        
        long currentSlot = currentPoint.getSlot();
        ChainTip headerTip = getHeaderTip();
        
        if (headerTip == null || currentSlot >= headerTip.getSlot()) {
            return null;
        }
        
        // Find the next slot with a block/header after currentSlot
        Long nextSlot = blockNumberBySlot.keySet().stream()
                .filter(slot -> slot > currentSlot)
                .min(Long::compareTo)
                .orElse(null);
                
        if (nextSlot != null) {
            Long blockNumber = blockNumberBySlot.get(nextSlot);
            if (blockNumber != null) {
                byte[] blockHash = blockHashByNumber.get(blockNumber);
                if (blockHash != null) {
                    return new Point(nextSlot, HexUtil.encodeHexString(blockHash));
                }
            }
        }
        
        return null;
    }

    @Override
    public Point findNextBlock(Point currentPoint) {
        if (currentPoint == null) {
            return null;
        }

        // Special case: if asking for next block after Point.ORIGIN, return our first block
        if (currentPoint.getSlot() == 0 && currentPoint.getHash() == null) {
            return getFirstBlock();
        }

        try {
            // Find the current block's number
            Long currentBlockNumber = null;

            if (currentPoint.getHash() != null) {
                byte[] currentBlockHash = HexUtil.decodeHexString(currentPoint.getHash());
                // Find block number by scanning through our stored blocks
                for (Map.Entry<Long, byte[]> entry : blockHashByNumber.entrySet()) {
                    if (Arrays.equals(entry.getValue(), currentBlockHash)) {
                        currentBlockNumber = entry.getKey();
                        break;
                    }
                }
            }

            if (currentBlockNumber == null) {
                // Try to find by slot
                currentBlockNumber = blockNumberBySlot.get(currentPoint.getSlot());
            }

            if (currentBlockNumber != null) {
                // Get the next block
                long nextBlockNumber = currentBlockNumber + 1;
                byte[] nextBlockHash = blockHashByNumber.get(nextBlockNumber);

                if (nextBlockHash != null) {
                    // Find the slot for this block
                    Long nextSlot = null;
                    for (Map.Entry<Long, Long> entry : blockNumberBySlot.entrySet()) {
                        if (entry.getValue().equals(nextBlockNumber)) {
                            nextSlot = entry.getKey();
                            break;
                        }
                    }

                    if (nextSlot != null) {
                        return new Point(nextSlot, HexUtil.encodeHexString(nextBlockHash));
                    }
                }
            }
        } catch (Exception e) {
            // Log error and return null
            if (log.isDebugEnabled()) {
                log.debug("Error finding next block: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Find the first (earliest) block in our chain
     */
    public Point getFirstBlock() {
        if (blockHashByNumber.isEmpty()) {
            return null;
        }

        try {
            // Find the minimum block number
            long minBlockNumber = blockHashByNumber.keySet().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(-1);

            if (minBlockNumber >= 0) {
                byte[] firstBlockHash = blockHashByNumber.get(minBlockNumber);

                // Find the slot for this block
                Long firstSlot = null;
                for (Map.Entry<Long, Long> entry : blockNumberBySlot.entrySet()) {
                    if (entry.getValue().equals(minBlockNumber)) {
                        firstSlot = entry.getKey();
                        break;
                    }
                }

                if (firstBlockHash != null && firstSlot != null) {
                    return new Point(firstSlot, HexUtil.encodeHexString(firstBlockHash));
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error finding first block: {}", e.getMessage());
            }
        }

        return null;
    }

    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        List<Point> blocks = new ArrayList<>();

        if (from == null || to == null) {
            return blocks;
        }

        try {
            // Special case: if from is Point.ORIGIN, start from our first block
            Point startPoint = from;
            if (from.getSlot() == 0 && from.getHash() == null) {
                startPoint = getFirstBlock();
                if (startPoint == null) {
                    return blocks; // No blocks in our chain
                }
            }

            // Get the block numbers for start and end points
            Long startBlockNumber = getBlockNumberForPoint(startPoint);
            Long endBlockNumber = getBlockNumberForPoint(to);

            if (startBlockNumber == null || endBlockNumber == null) {
                return blocks; // Can't find block numbers for the points
            }

            // Ensure we traverse in the correct direction
            long minBlockNumber = Math.min(startBlockNumber, endBlockNumber);
            long maxBlockNumber = Math.max(startBlockNumber, endBlockNumber);

            // Collect all blocks in the range
            for (long blockNum = minBlockNumber; blockNum <= maxBlockNumber; blockNum++) {
                byte[] blockHash = blockHashByNumber.get(blockNum);
                if (blockHash != null) {
                    // Find the slot for this block
                    Long slot = null;
                    for (Map.Entry<Long, Long> entry : blockNumberBySlot.entrySet()) {
                        if (entry.getValue().equals(blockNum)) {
                            slot = entry.getKey();
                            break;
                        }
                    }

                    if (slot != null) {
                        blocks.add(new Point(slot, HexUtil.encodeHexString(blockHash)));
                    }
                }
            }

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error finding blocks in range: {}", e.getMessage());
            }
        }

        return blocks;
    }

    /**
     * Helper method to get block number for a given point
     */
    private Long getBlockNumberForPoint(Point point) {
        if (point == null) {
            return null;
        }

        // Try to find by hash first
        if (point.getHash() != null) {
            try {
                byte[] blockHash = HexUtil.decodeHexString(point.getHash());
                for (Map.Entry<Long, byte[]> entry : blockHashByNumber.entrySet()) {
                    if (Arrays.equals(entry.getValue(), blockHash)) {
                        return entry.getKey();
                    }
                }
            } catch (Exception e) {
                // Fall through to slot-based lookup
            }
        }

        // Try to find by slot
        return blockNumberBySlot.get(point.getSlot());
    }

    @Override
    public boolean hasPoint(Point point) {
        if (point == null) {
            return false;
        }

        // Handle genesis point (Point.ORIGIN with slot=0, hash=null)
        if (point.getSlot() == 0 && point.getHash() == null) {
            // Genesis point is always considered valid if we have any blocks
            return tip != null;
        }

        // Handle normal points with hash
        if (point.getHash() == null) {
            return false;
        }

        try {
            byte[] blockHash = HexUtil.decodeHexString(point.getHash());
            return blockStore.containsKey(blockHash) || blockHeaderStore.containsKey(blockHash);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Point findLastPointAfterNBlocks(Point from, long batchSize) {
        if (from == null) {
            return null;
        }

        try {
            long fromSlot = from.getSlot();
            
            // Get all available slots starting from the fromSlot in order
            List<Long> sortedSlots = blockNumberBySlot.keySet().stream()
                    .filter(slot -> slot >= fromSlot)
                    .sorted()
                    .limit(batchSize)
                    .toList();

            if (sortedSlots.isEmpty()) {
                return null;
            }

            // Get the last slot and its corresponding block
            Long lastSlot = sortedSlots.get(sortedSlots.size() - 1);
            Long lastBlockNumber = blockNumberBySlot.get(lastSlot);
            
            if (lastBlockNumber != null) {
                byte[] lastBlockHash = blockHashByNumber.get(lastBlockNumber);
                if (lastBlockHash != null) {
                    return new Point(lastSlot, HexUtil.encodeHexString(lastBlockHash));
                }
            }

            return null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error finding last point after N blocks: {}", e.getMessage());
            }
            return null;
        }
    }

    @Override
    public Long getBlockNumberBySlot(Long slot) {
        return blockNumberBySlot.get(slot);
    }
}
