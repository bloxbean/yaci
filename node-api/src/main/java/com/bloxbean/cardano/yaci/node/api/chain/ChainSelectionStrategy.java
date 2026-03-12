package com.bloxbean.cardano.yaci.node.api.chain;

/**
 * Pluggable strategy for selecting the best chain among competing candidates.
 * <p>
 * Implementations decide which chain tip to follow when multiple upstream peers
 * present different views. The default implementation uses Praos-style selection
 * (longest chain by block number, smallest slot tie-break).
 * <p>
 * Future implementations may use VRF-based tie-breaking when a Java VRF library
 * becomes available.
 */
public interface ChainSelectionStrategy {

    /**
     * Compare two chain candidates.
     *
     * @return positive if {@code a} is preferred over {@code b},
     *         negative if {@code b} is preferred,
     *         zero if they are equally preferred
     */
    int compare(ChainCandidate a, ChainCandidate b);

    /**
     * Maximum rollback depth (the security parameter k).
     * Candidates whose fork depth exceeds this value should be rejected.
     * <p>
     * Default values from Shelley genesis: mainnet/preprod=2160, preview=432, devnet=100.
     */
    long maxRollbackDepth();
}
