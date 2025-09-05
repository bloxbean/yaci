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
    public boolean hasBlock(byte[] blockHash) {
        return blockStore.containsKey(blockHash);
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
        // Slot-first: iterate by slot order up to header tip, independent of block number continuity
        if (currentPoint == null) return null;

        long currentSlot = currentPoint.getSlot();
        ChainTip headerTip = getHeaderTip();

        if (headerTip == null || currentSlot >= headerTip.getSlot()) return null;

        Long nextSlot = blockNumberBySlot.higherKey(currentSlot);
        if (nextSlot == null) return null;

        Long blockNumber = blockNumberBySlot.get(nextSlot);
        if (blockNumber == null) return null;

        byte[] blockHash = blockHashByNumber.get(blockNumber);
        if (blockHash == null) return null;

        return new Point(nextSlot, HexUtil.encodeHexString(blockHash));
    }

    @Override
    public Point findNextBlock(Point currentPoint) {
        // Slot-first: behave like findNextBlockHeader but bounded by body tip
        if (currentPoint == null) return null;

        // If ORIGIN, return the first block we have
        if (currentPoint.getSlot() == 0 && currentPoint.getHash() == null) {
            return getFirstBlock();
        }

        long currentSlot = currentPoint.getSlot();
        ChainTip bodyTip = getTip();
        if (bodyTip == null || currentSlot >= bodyTip.getSlot()) return null;

        Long nextSlot = blockNumberBySlot.higherKey(currentSlot);
        if (nextSlot == null) return null;

        Long blockNumber = blockNumberBySlot.get(nextSlot);
        if (blockNumber == null) return null;

        byte[] blockHash = blockHashByNumber.get(blockNumber);
        if (blockHash == null) return null;

        return new Point(nextSlot, HexUtil.encodeHexString(blockHash));
    }

    /**
     * Find the first (earliest) block in our chain
     */
    public Point getFirstBlock() {
        // Slot-first: earliest slot in our map
        if (blockNumberBySlot.isEmpty()) return null;

        try {
            Long firstSlot = blockNumberBySlot.firstKey();
            if (firstSlot == null) return null;

            Long blockNumber = blockNumberBySlot.get(firstSlot);
            if (blockNumber == null) return null;

            byte[] blockHash = blockHashByNumber.get(blockNumber);
            if (blockHash == null) return null;

            return new Point(firstSlot, HexUtil.encodeHexString(blockHash));
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Error finding first block: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        // Slot-first: return points between slots [min(from.slot, to.slot), max] inclusive
        List<Point> out = new ArrayList<>();
        if (from == null || to == null) return out;

        try {
            // Resolve start slot (handle ORIGIN)
            Long startSlot = from.getSlot() == 0 && from.getHash() == null
                    ? blockNumberBySlot.isEmpty() ? null : blockNumberBySlot.firstKey()
                    : from.getSlot();
            Long endSlot = to.getSlot();
            if (startSlot == null || endSlot == null) return out;

            long minSlot = Math.min(startSlot, endSlot);
            long maxSlot = Math.max(startSlot, endSlot);

            // Iterate by slot order
            for (Map.Entry<Long, Long> entry : blockNumberBySlot.subMap(minSlot, true, maxSlot, true).entrySet()) {
                Long slot = entry.getKey();
                Long blockNumber = entry.getValue();
                byte[] blockHash = blockHashByNumber.get(blockNumber);
                if (blockHash != null) {
                    out.add(new Point(slot, HexUtil.encodeHexString(blockHash)));
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Error finding blocks in range: {}", e.getMessage());
        }
        return out;
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
