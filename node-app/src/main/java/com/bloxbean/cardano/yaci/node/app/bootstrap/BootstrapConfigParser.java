package com.bloxbean.cardano.yaci.node.app.bootstrap;

import com.bloxbean.cardano.yaci.node.api.config.BootstrapOutpointConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses bootstrap configuration values from simple string formats.
 */
public class BootstrapConfigParser {

    /**
     * Parse a list of UTXO strings in "txHash#outputIndex" format.
     * @param refs list of strings, each in "txHash#outputIndex" format
     * @return parsed list of {@link BootstrapOutpointConfig}, or null if input is null/empty
     * @throws IllegalArgumentException with specific message on parse failure
     */
    public static List<BootstrapOutpointConfig> parseUtxoRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) return null;
        List<BootstrapOutpointConfig> result = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            String s = refs.get(i).trim();
            int hash = s.lastIndexOf('#');
            if (hash < 0 || hash == 0 || hash == s.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid bootstrap UTXO at index " + i + ": '" + s
                                + "'. Expected format: txHash#outputIndex (e.g. abc123...def#0)");
            }
            String txHash = s.substring(0, hash);
            int outputIndex;
            try {
                outputIndex = Integer.parseInt(s.substring(hash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid output index in bootstrap UTXO at index " + i + ": '" + s
                                + "'. Output index must be a number.");
            }
            if (outputIndex < 0) {
                throw new IllegalArgumentException(
                        "Negative output index in bootstrap UTXO at index " + i + ": '" + s + "'.");
            }
            result.add(BootstrapOutpointConfig.builder()
                    .txHash(txHash).outputIndex(outputIndex).build());
        }
        return result.isEmpty() ? null : result;
    }
}
