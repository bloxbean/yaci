package com.bloxbean.cardano.yaci.node.api.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a specific UTXO to bootstrap by tx hash and output index.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BootstrapOutpointConfig {
    private String txHash;
    private int outputIndex;
}
