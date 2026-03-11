package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.api.plugin.StorageFilter;
import com.bloxbean.cardano.yaci.node.api.plugin.UtxoFilterContext;

import java.util.Collections;
import java.util.Set;

/**
 * Built-in storage filter that only stores UTXOs for configured addresses
 * or payment credential hashes. If both sets are empty, all outputs pass.
 * <p>
 * Configured via YAML:
 * <pre>
 * yaci:
 *   node:
 *     filters:
 *       utxo:
 *         enabled: true
 *         addresses:
 *           - "addr1qx..."
 *         payment-credentials:
 *           - "abcd1234..."
 * </pre>
 */
public final class AddressUtxoFilter implements StorageFilter {

    private final Set<String> addresses;
    private final Set<String> paymentCredHashes;

    public AddressUtxoFilter(Set<String> addresses, Set<String> paymentCredHashes) {
        this.addresses = addresses != null ? Set.copyOf(addresses) : Collections.emptySet();
        this.paymentCredHashes = paymentCredHashes != null ? Set.copyOf(paymentCredHashes) : Collections.emptySet();
    }

    @Override
    public boolean acceptUtxoOutput(UtxoFilterContext ctx, Block block, TransactionBody txBody) {
        if (addresses.isEmpty() && paymentCredHashes.isEmpty()) return true;
        if (ctx.address() != null && addresses.contains(ctx.address())) return true;
        if (ctx.paymentCredentialHash() != null && paymentCredHashes.contains(ctx.paymentCredentialHash())) return true;
        return false;
    }

    @Override
    public int priority() {
        return 50; // run before plugin-provided filters (default 100)
    }
}
