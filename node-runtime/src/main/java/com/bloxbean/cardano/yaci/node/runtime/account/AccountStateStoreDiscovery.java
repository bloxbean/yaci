package com.bloxbean.cardano.yaci.node.runtime.account;

import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreContext;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.ServiceLoader;

/**
 * Discovers the highest-priority available {@link AccountStateStoreProvider} via {@link ServiceLoader}
 * and creates the corresponding {@link AccountStateStore}.
 */
@Slf4j
public final class AccountStateStoreDiscovery {

    private AccountStateStoreDiscovery() {}

    /**
     * Discover and create the account state store.
     *
     * @param context context passed to providers
     * @param cl      classloader for ServiceLoader discovery
     * @return the created store from the highest-priority available provider
     * @throws IllegalStateException if no provider is available
     */
    public static AccountStateStore discover(AccountStateStoreContext context, ClassLoader cl) {
        ServiceLoader<AccountStateStoreProvider> loader =
                ServiceLoader.load(AccountStateStoreProvider.class, cl);

        AccountStateStoreProvider selected = selectProvider(loader, context);

        log.info("AccountStateStore provider: {} (priority={})", selected.name(), selected.priority());
        return selected.create(context);
    }

    /**
     * Select the highest-priority available provider from the given candidates.
     * Package-private for testability.
     */
    static AccountStateStoreProvider selectProvider(Iterable<AccountStateStoreProvider> providers,
                                                    AccountStateStoreContext context) {
        AccountStateStoreProvider selected = null;
        for (AccountStateStoreProvider provider : providers) {
            log.debug("Found AccountStateStoreProvider: {} (priority={})", provider.name(), provider.priority());
            if (provider.isAvailable(context)) {
                if (selected == null || provider.priority() > selected.priority()) {
                    selected = provider;
                } else if (provider.priority() == selected.priority()) {
                    log.warn("Multiple AccountStateStoreProviders with priority {}: {} and {}. Keeping {}.",
                            provider.priority(), selected.name(), provider.name(), selected.name());
                }
            }
        }

        if (selected == null) {
            throw new IllegalStateException("No AccountStateStoreProvider available");
        }
        return selected;
    }
}
