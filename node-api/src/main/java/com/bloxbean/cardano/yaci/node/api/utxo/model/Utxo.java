package com.bloxbean.cardano.yaci.node.api.utxo.model;

import java.math.BigInteger;
import java.util.List;

/**
 * Public-facing UTXO view for queries.
 * Implementations may compute some fields lazily.
 */
public record Utxo(
        Outpoint outpoint,
        String address,                    // bech32 or raw hex
        BigInteger lovelace,               // ADA amount
        List<AssetAmount> assets,          // other assets (policyId, name, quantity)
        String datumHash,                  // optional
        byte[] inlineDatum,                // optional
        String referenceScriptHash,        // optional
        boolean collateralReturn,          // true if collateral return output
        long slot,                         // created at slot
        long blockNumber,                  // created at block
        String blockHash                   // created at block hash
) {}

