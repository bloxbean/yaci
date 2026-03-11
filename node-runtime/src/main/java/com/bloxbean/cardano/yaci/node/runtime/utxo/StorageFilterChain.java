package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.api.plugin.StorageFilter;
import com.bloxbean.cardano.yaci.node.api.plugin.UtxoFilterContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Composes multiple {@link StorageFilter} instances into a chain.
 * All filters must accept for an output to pass.
 */
public final class StorageFilterChain {

    private final List<StorageFilter> filters;

    public StorageFilterChain(List<StorageFilter> filters) {
        this.filters = new ArrayList<>(filters);
        this.filters.sort(Comparator.comparingInt(StorageFilter::priority));
    }

    /**
     * Returns true if all filters accept the UTXO output, or if no filters are configured.
     */
    public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
        for (StorageFilter filter : filters) {
            if (!filter.acceptUtxoOutput(ctx, block, txBody)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if this chain has no filters (pass-through).
     */
    public boolean isEmpty() {
        return filters.isEmpty();
    }

    public int size() {
        return filters.size();
    }
}
