package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.api.plugin.StorageFilter;
import com.bloxbean.cardano.yaci.node.api.plugin.UtxoFilterContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageFilterChainTest {

    private UtxoFilterContext ctx(String address, String paymentCred) {
        return new UtxoFilterContext(address, paymentCred, 1_000_000L, List.of(), 100L, 1L, "aa".repeat(32), 0);
    }

    @Test
    void emptyChain_acceptsAll() {
        var chain = new StorageFilterChain(List.of());
        assertTrue(chain.isEmpty());
        assertTrue(chain.acceptUtxoOutput(ctx("addr1", null), null, null));
    }

    @Test
    void singleFilter_rejects() {
        StorageFilter rejectAll = new StorageFilter() {
            @Override
            public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
                return false;
            }
        };
        var chain = new StorageFilterChain(List.of(rejectAll));
        assertFalse(chain.isEmpty());
        assertFalse(chain.acceptUtxoOutput(ctx("addr1", null), null, null));
    }

    @Test
    void multipleFilters_allMustAccept() {
        StorageFilter acceptAll = new StorageFilter() {};
        StorageFilter rejectAll = new StorageFilter() {
            @Override
            public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
                return false;
            }
        };
        var chain = new StorageFilterChain(List.of(acceptAll, rejectAll));
        assertFalse(chain.acceptUtxoOutput(ctx("addr1", null), null, null));
    }

    @Test
    void priorityOrdering_lowerPriorityRunsFirst() {
        // Track execution order
        StringBuilder order = new StringBuilder();
        StorageFilter first = new StorageFilter() {
            @Override
            public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
                order.append("A");
                return true;
            }
            @Override public int priority() { return 10; }
        };
        StorageFilter second = new StorageFilter() {
            @Override
            public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
                order.append("B");
                return true;
            }
            @Override public int priority() { return 50; }
        };
        // Add in reverse priority order
        var chain = new StorageFilterChain(List.of(second, first));
        chain.acceptUtxoOutput(ctx("addr1", null), null, null);
        assertEquals("AB", order.toString());
    }

    @Test
    void earlyRejection_skipsRemainingFilters() {
        StringBuilder order = new StringBuilder();
        StorageFilter rejecter = new StorageFilter() {
            @Override
            public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
                order.append("R");
                return false;
            }
            @Override public int priority() { return 1; }
        };
        StorageFilter neverReached = new StorageFilter() {
            @Override
            public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
                order.append("N");
                return true;
            }
            @Override public int priority() { return 100; }
        };
        var chain = new StorageFilterChain(List.of(rejecter, neverReached));
        assertFalse(chain.acceptUtxoOutput(ctx("addr1", null), null, null));
        assertEquals("R", order.toString());
    }
}
