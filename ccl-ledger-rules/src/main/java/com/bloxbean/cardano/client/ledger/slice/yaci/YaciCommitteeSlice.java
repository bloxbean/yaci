package com.bloxbean.cardano.client.ledger.slice.yaci;

import com.bloxbean.cardano.client.ledger.slice.CommitteeSlice;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;

import java.util.Optional;

/**
 * Yaci adapter for {@link CommitteeSlice} backed by {@link LedgerStateProvider}.
 */
public class YaciCommitteeSlice implements CommitteeSlice {

    private final LedgerStateProvider provider;

    public YaciCommitteeSlice(LedgerStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean isMember(String coldCredentialHash) {
        return provider.isCommitteeMember(0, coldCredentialHash)
                || provider.isCommitteeMember(1, coldCredentialHash);
    }

    @Override
    public Optional<String> getHotCredential(String coldCredentialHash) {
        Optional<String> result = provider.getCommitteeHotCredential(0, coldCredentialHash);
        if (result.isPresent()) return result;
        return provider.getCommitteeHotCredential(1, coldCredentialHash);
    }

    @Override
    public boolean hasResigned(String coldCredentialHash) {
        return provider.hasCommitteeMemberResigned(0, coldCredentialHash)
                || provider.hasCommitteeMemberResigned(1, coldCredentialHash);
    }
}
