package com.bloxbean.cardano.yaci.node.api.bootstrap;

import java.math.BigInteger;

/**
 * A native asset held in a bootstrap UTXO.
 *
 * @param policyId  policy ID (hex, 56 chars)
 * @param assetName asset name (hex-encoded)
 * @param quantity  token quantity
 */
public record BootstrapAsset(
        String policyId,
        String assetName,
        BigInteger quantity
) {}
