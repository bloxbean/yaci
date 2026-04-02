package com.bloxbean.cardano.client.ledger.slice.yaci;

import com.bloxbean.cardano.client.ledger.slice.PoolsSlice;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;

/**
 * Yaci adapter for {@link PoolsSlice} backed by {@link LedgerStateProvider}.
 */
public class YaciPoolsSlice implements PoolsSlice {

    private final LedgerStateProvider provider;

    public YaciPoolsSlice(LedgerStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean isRegistered(String poolId) {
        return provider.isPoolRegistered(poolId);
    }

    @Override
    public long getRetirementEpoch(String poolId) {
        return provider.getPoolRetirementEpoch(poolId).orElse(-1L);
    }
}
