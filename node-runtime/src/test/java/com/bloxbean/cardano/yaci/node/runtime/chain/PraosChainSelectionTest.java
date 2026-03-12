package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.node.api.chain.ChainCandidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PraosChainSelectionTest {

    private final PraosChainSelection strategy = new PraosChainSelection(2160);

    @Test
    void higherBlockNumberWins() {
        ChainCandidate a = candidate("peer-a", 1000, 5000);
        ChainCandidate b = candidate("peer-b", 999, 5000);

        assertThat(strategy.compare(a, b)).isPositive();
        assertThat(strategy.compare(b, a)).isNegative();
    }

    @Test
    void sameBlockNumber_smallerSlotWins() {
        // Amaru approach: tie-break by smallest slot
        ChainCandidate a = candidate("peer-a", 1000, 4999);
        ChainCandidate b = candidate("peer-b", 1000, 5000);

        assertThat(strategy.compare(a, b)).isPositive(); // a wins (smaller slot)
        assertThat(strategy.compare(b, a)).isNegative(); // b loses
    }

    @Test
    void equalCandidatesReturnZero() {
        ChainCandidate a = candidate("peer-a", 1000, 5000);
        ChainCandidate b = candidate("peer-b", 1000, 5000);

        assertThat(strategy.compare(a, b)).isZero();
    }

    @Test
    void blockNumberTakesPrecedenceOverSlot() {
        // Higher block number wins even with larger slot
        ChainCandidate a = candidate("peer-a", 1001, 6000);
        ChainCandidate b = candidate("peer-b", 1000, 5000);

        assertThat(strategy.compare(a, b)).isPositive();
    }

    @Test
    void maxRollbackDepthReturnsK() {
        assertThat(strategy.maxRollbackDepth()).isEqualTo(2160);

        PraosChainSelection previewStrategy = new PraosChainSelection(432);
        assertThat(previewStrategy.maxRollbackDepth()).isEqualTo(432);
    }

    @Test
    void invalidSecurityParamThrows() {
        assertThatThrownBy(() -> new PraosChainSelection(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PraosChainSelection(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void longestChainAcrossMultipleCandidates() {
        ChainCandidate a = candidate("peer-a", 100, 500);
        ChainCandidate b = candidate("peer-b", 200, 1000);
        ChainCandidate c = candidate("peer-c", 150, 750);

        // b should be best (highest block number)
        assertThat(strategy.compare(b, a)).isPositive();
        assertThat(strategy.compare(b, c)).isPositive();
    }

    private ChainCandidate candidate(String peerId, long blockNumber, long slot) {
        return new ChainCandidate(peerId, blockNumber, slot,
                "hash-" + peerId + "-" + blockNumber, null, 0);
    }
}
