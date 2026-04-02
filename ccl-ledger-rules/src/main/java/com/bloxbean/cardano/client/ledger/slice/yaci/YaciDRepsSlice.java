package com.bloxbean.cardano.client.ledger.slice.yaci;

import com.bloxbean.cardano.client.ledger.slice.DRepsSlice;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Yaci adapter for {@link DRepsSlice} backed by {@link LedgerStateProvider}.
 */
public class YaciDRepsSlice implements DRepsSlice {

    private final LedgerStateProvider provider;

    public YaciDRepsSlice(LedgerStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean isRegistered(String drepCredentialHash) {
        // Try credType=0 (key hash) then credType=1 (script hash)
        return provider.isDRepRegistered(0, drepCredentialHash)
                || provider.isDRepRegistered(1, drepCredentialHash);
    }

    @Override
    public Optional<BigInteger> getDeposit(String drepCredentialHash) {
        Optional<BigInteger> result = provider.getDRepDeposit(0, drepCredentialHash);
        if (result.isPresent()) return result;
        return provider.getDRepDeposit(1, drepCredentialHash);
    }
}
