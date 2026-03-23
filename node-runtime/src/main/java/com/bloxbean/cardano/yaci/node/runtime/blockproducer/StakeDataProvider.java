package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import java.math.BigInteger;

/**
 * Interface for retrieving stake data needed for slot leader selection.
 * Implementations may fetch from yaci-store REST API, cardano-db-sync, or other sources.
 */
public interface StakeDataProvider {

    /**
     * Get the active stake for a specific pool in a given epoch.
     *
     * @param poolHash hex-encoded pool hash (blake2b_224 of cold verification key)
     * @param epoch    the epoch number
     * @return active stake in lovelace, or null if not available
     */
    BigInteger getPoolStake(String poolHash, int epoch);

    /**
     * Get the total active stake for a given epoch.
     *
     * @param epoch the epoch number
     * @return total active stake in lovelace, or null if not available
     */
    BigInteger getTotalStake(int epoch);
}
