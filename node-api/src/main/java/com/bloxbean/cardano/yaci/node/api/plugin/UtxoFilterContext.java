package com.bloxbean.cardano.yaci.node.api.plugin;

import com.bloxbean.cardano.yaci.core.model.Amount;

import java.util.List;

/**
 * Context object providing UTXO-specific fields for filtering decisions.
 * Passed to {@link StorageFilter#acceptUtxoOutput} for each transaction output.
 *
 * @param address            bech32 or hex address of the output
 * @param paymentCredentialHash 28-byte payment credential hash (hex), or null if extraction fails
 * @param lovelace           lovelace value of the output
 * @param assets             multi-asset amounts (may be null or empty)
 * @param slot               slot number of the containing block
 * @param blockNumber        block number of the containing block
 * @param txHash             transaction hash (hex)
 * @param outputIndex        output index within the transaction
 */
public record UtxoFilterContext(
        String address,
        String paymentCredentialHash,
        long lovelace,
        List<Amount> assets,
        long slot,
        long blockNumber,
        String txHash,
        int outputIndex
) {}
