package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Amount;

import java.math.BigInteger;
import java.util.List;

final class MultiEraOutput {
    final String address;
    final BigInteger lovelace;
    final List<Amount> assets; // multi-assets only; lovelace excluded
    final String datumHash; // hex, optional
    final byte[] inlineDatum; // raw CBOR, optional
    final String scriptRefHex; // hex, optional
    final boolean collateralReturn;

    MultiEraOutput(String address,
                   BigInteger lovelace,
                   List<Amount> assets,
                   String datumHash,
                   byte[] inlineDatum,
                   String scriptRefHex,
                   boolean collateralReturn) {
        this.address = address;
        this.lovelace = lovelace;
        this.assets = assets;
        this.datumHash = datumHash;
        this.inlineDatum = inlineDatum;
        this.scriptRefHex = scriptRefHex;
        this.collateralReturn = collateralReturn;
    }
}

