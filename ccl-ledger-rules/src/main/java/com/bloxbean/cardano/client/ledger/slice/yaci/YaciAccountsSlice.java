package com.bloxbean.cardano.client.ledger.slice.yaci;

import com.bloxbean.cardano.client.ledger.slice.AccountsSlice;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Yaci adapter for {@link AccountsSlice} backed by {@link LedgerStateProvider}.
 * Uses the type-aware methods to query with both credType and hash.
 */
public class YaciAccountsSlice implements AccountsSlice {

    private final LedgerStateProvider provider;

    public YaciAccountsSlice(LedgerStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean isRegistered(String credentialHash) {
        // Try credType=0 (key hash) then credType=1 (script hash)
        return provider.isStakeCredentialRegistered(0, credentialHash)
                || provider.isStakeCredentialRegistered(1, credentialHash);
    }

    @Override
    public boolean isRegistered(int credType, String credentialHash) {
        return provider.isStakeCredentialRegistered(credType, credentialHash);
    }

    @Override
    public Optional<BigInteger> getRewardBalance(String credentialHash) {
        // Try credType=0 then credType=1
        Optional<BigInteger> result = provider.getRewardBalance(0, credentialHash);
        if (result.isPresent()) return result;
        return provider.getRewardBalance(1, credentialHash);
    }

    @Override
    public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
        return provider.getRewardBalance(credType, credentialHash);
    }

    @Override
    public Optional<BigInteger> getDeposit(String credentialHash) {
        Optional<BigInteger> result = provider.getStakeDeposit(0, credentialHash);
        if (result.isPresent()) return result;
        return provider.getStakeDeposit(1, credentialHash);
    }

    @Override
    public Optional<BigInteger> getDeposit(int credType, String credentialHash) {
        return provider.getStakeDeposit(credType, credentialHash);
    }
}
