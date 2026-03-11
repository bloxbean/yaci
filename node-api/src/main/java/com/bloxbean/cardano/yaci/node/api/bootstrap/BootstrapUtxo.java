package com.bloxbean.cardano.yaci.node.api.bootstrap;

import java.math.BigInteger;
import java.util.List;

/**
 * A UTXO fetched from a bootstrap data provider.
 *
 * @param txHash          transaction hash (hex)
 * @param outputIndex     output index
 * @param address         bech32 address
 * @param lovelace        ADA amount in lovelace
 * @param assets          native assets (may be empty)
 * @param datumHash       datum hash (hex), nullable
 * @param inlineDatumCbor inline datum CBOR (hex), nullable
 * @param scriptRefCbor   script reference CBOR (hex), nullable
 */
public record BootstrapUtxo(
        String txHash,
        int outputIndex,
        String address,
        BigInteger lovelace,
        List<BootstrapAsset> assets,
        String datumHash,
        String inlineDatumCbor,
        String scriptRefCbor
) {}
