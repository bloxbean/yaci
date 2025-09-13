package com.bloxbean.cardano.yaci.node.runtime.utxo;

import java.util.List;

final class MultiEraTx {
    final String txHash;
    final List<MultiEraInput> inputs;
    final List<MultiEraOutput> outputs;
    final List<MultiEraInput> collateralInputs;
    final MultiEraOutput collateralReturn;
    final boolean invalid;

    MultiEraTx(String txHash,
               List<MultiEraInput> inputs,
               List<MultiEraOutput> outputs,
               List<MultiEraInput> collateralInputs,
               MultiEraOutput collateralReturn,
               boolean invalid) {
        this.txHash = txHash;
        this.inputs = inputs;
        this.outputs = outputs;
        this.collateralInputs = collateralInputs;
        this.collateralReturn = collateralReturn;
        this.invalid = invalid;
    }
}

