package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreContext;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreProvider;

/**
 * In-memory fallback {@link AccountStateStoreProvider} (priority -100).
 * Always available; used when no higher-priority provider is found.
 */
public class InMemoryAccountStateStoreProvider implements AccountStateStoreProvider {

    @Override
    public int priority() {
        return -100;
    }

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public boolean isAvailable(AccountStateStoreContext context) {
        return true;
    }

    @Override
    public AccountStateStore create(AccountStateStoreContext context) {
        return new InMemoryAccountStateStore(true, context.epochParamProvider());
    }
}
