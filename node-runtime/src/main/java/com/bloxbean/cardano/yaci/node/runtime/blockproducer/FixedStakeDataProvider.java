package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import java.math.BigInteger;

/**
 * A fixed stake data provider for devnet single-pool mode.
 * Returns identical pool and total stake values so that sigma = 1.0,
 * ensuring every slot passes the VRF leader check when activeSlotsCoeff = 1.0.
 */
public class FixedStakeDataProvider implements StakeDataProvider {

    private static final BigInteger FIXED_STAKE = BigInteger.valueOf(1_000_000_000_000L); // 1M ADA in lovelace

    @Override
    public BigInteger getPoolStake(String poolHash, int epoch) {
        return FIXED_STAKE;
    }

    @Override
    public BigInteger getTotalStake(int epoch) {
        return FIXED_STAKE;
    }
}
