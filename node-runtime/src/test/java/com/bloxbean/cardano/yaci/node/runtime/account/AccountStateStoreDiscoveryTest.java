package com.bloxbean.cardano.yaci.node.runtime.account;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreContext;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStoreProvider;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountStateStoreDiscoveryTest {

    private static final EpochParamProvider ZERO_PROVIDER = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
    };

    private static final AccountStateStoreContext CTX = new AccountStateStoreContext(
            null, Map.of(), LoggerFactory.getLogger(AccountStateStoreDiscoveryTest.class), ZERO_PROVIDER);

    @Test
    void highestPriorityWins() {
        var low = new StubProvider("low", 0, true);
        var high = new StubProvider("high", 10, true);

        var selected = AccountStateStoreDiscovery.selectProvider(List.of(low, high), CTX);
        assertThat(selected.name()).isEqualTo("high");
    }

    @Test
    void unavailableProviderSkipped() {
        var unavailable = new StubProvider("unavailable", 100, false);
        var available = new StubProvider("available", 0, true);

        var selected = AccountStateStoreDiscovery.selectProvider(List.of(unavailable, available), CTX);
        assertThat(selected.name()).isEqualTo("available");
    }

    @Test
    void noProviderAvailable_throwsIllegalState() {
        var unavailable = new StubProvider("unavailable", 0, false);

        assertThatThrownBy(() -> AccountStateStoreDiscovery.selectProvider(List.of(unavailable), CTX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AccountStateStoreProvider available");
    }

    @Test
    void emptyProviderList_throwsIllegalState() {
        assertThatThrownBy(() -> AccountStateStoreDiscovery.selectProvider(List.of(), CTX))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Simple stub provider — avoids Mockito ByteBuddy issues with Java 25. */
    private static class StubProvider implements AccountStateStoreProvider {
        private final String name;
        private final int priority;
        private final boolean available;

        StubProvider(String name, int priority, boolean available) {
            this.name = name;
            this.priority = priority;
            this.available = available;
        }

        @Override public int priority() { return priority; }
        @Override public String name() { return name; }
        @Override public boolean isAvailable(AccountStateStoreContext context) { return available; }

        @Override
        public AccountStateStore create(AccountStateStoreContext context) {
            return new NoOpAccountStateStore();
        }
    }

    /** Minimal no-op store for test stubs. */
    private static class NoOpAccountStateStore implements AccountStateStore {
        @Override public boolean isEnabled() { return true; }
        @Override public void applyBlock(BlockAppliedEvent event) {}
        @Override public void rollbackTo(RollbackEvent event) {}
        @Override public void reconcile(ChainState chainState) {}
        @Override public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) { return Optional.empty(); }
        @Override public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) { return Optional.empty(); }
        @Override public Optional<String> getDelegatedPool(int credType, String credentialHash) { return Optional.empty(); }
        @Override public Optional<LedgerStateProvider.DRepDelegation> getDRepDelegation(int credType, String credentialHash) { return Optional.empty(); }
        @Override public boolean isStakeCredentialRegistered(int credType, String credentialHash) { return false; }
        @Override public BigInteger getTotalDeposited() { return BigInteger.ZERO; }
        @Override public boolean isPoolRegistered(String poolHash) { return false; }
        @Override public Optional<BigInteger> getPoolDeposit(String poolHash) { return Optional.empty(); }
        @Override public Optional<Long> getPoolRetirementEpoch(String poolHash) { return Optional.empty(); }
    }
}
