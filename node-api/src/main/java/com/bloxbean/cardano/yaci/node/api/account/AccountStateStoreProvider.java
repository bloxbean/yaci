package com.bloxbean.cardano.yaci.node.api.account;

/**
 * SPI for pluggable {@link AccountStateStore} implementations.
 * Discovered via {@link java.util.ServiceLoader}; highest-priority available provider wins.
 *
 * <p>Built-in providers: RocksDB (priority 0), InMemory (priority -100).
 * External providers (e.g., yaci-store REST) use priority > 0 to override.
 */
public interface AccountStateStoreProvider {

    /**
     * Higher priority wins. Built-in RocksDB = 0, InMemory = -100.
     * External providers should use values > 0.
     */
    int priority();

    /** Human-readable name for logging. */
    String name();

    /**
     * Whether this provider can create a store in the current context.
     * Allows self-exclusion (e.g., a REST provider returns false if URL not configured).
     */
    boolean isAvailable(AccountStateStoreContext context);

    /**
     * Create the store. Called only when {@link #isAvailable} returned true.
     */
    AccountStateStore create(AccountStateStoreContext context);
}
