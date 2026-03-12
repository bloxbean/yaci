package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.node.api.chain.ChainCandidate;
import com.bloxbean.cardano.yaci.node.api.chain.ChainSelectionStrategy;

/**
 * A simple chain selection rule for now
 * <p>
 * Selection rules:
 * <ol>
 *   <li>Primary: highest block number (longest chain)</li>
 *   <li>Tie-break: smallest tip slot (Amaru approach — no Java VRF library available)</li>
 * </ol>
 * <p>
 * The rollback limit k is the security parameter from Shelley genesis ({@code securityParam}).
 * Default values: mainnet/preprod=2160, preview=432, devnet=100.
 * <p>
 * Future: swap to VRF-based tie-breaking when a Java VRF library becomes available.
 * The strategy is pluggable — zero application code changes needed.
 */
public class PraosChainSelection implements ChainSelectionStrategy {

    private final long securityParam;

    /**
     * @param securityParam the k parameter from Shelley genesis (e.g., 2160 for mainnet/preprod)
     */
    public PraosChainSelection(long securityParam) {
        if (securityParam <= 0) {
            throw new IllegalArgumentException("Security parameter k must be positive, got: " + securityParam);
        }
        this.securityParam = securityParam;
    }

    @Override
    public int compare(ChainCandidate a, ChainCandidate b) {
        // Primary: highest block number wins (longest chain)
        int blockCmp = Long.compare(a.blockNumber(), b.blockNumber());
        if (blockCmp != 0) {
            return blockCmp;
        }

        // Tie-break: smallest slot wins (Amaru approach)
        // Note: reversed comparison — smaller slot is preferred
        return Long.compare(b.slot(), a.slot());
    }

    @Override
    public long maxRollbackDepth() {
        return securityParam;
    }
}
