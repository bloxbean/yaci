package com.bloxbean.cardano.client.ledger.slice;

import java.util.Set;

/**
 * Accumulates required witnesses during validation.
 */
public interface WitnessSlice {

    void requireVKey(String vkeyHash);

    void requireScript(String scriptHash);

    Set<String> getRequiredVKeys();

    Set<String> getRequiredScripts();
}
