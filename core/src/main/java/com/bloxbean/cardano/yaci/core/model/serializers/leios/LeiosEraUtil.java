package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import com.bloxbean.cardano.yaci.core.model.Era;

/**
 * Maps the ns8 era index used by Leios transaction-list items.
 * This is intentionally separate from block-fetch era tags: ns8 uses
 * 0=Byron ... 6=Conway, 7=Dijkstra while block-fetch uses 0/1=Byron ... 8=Dijkstra.
 */
public final class LeiosEraUtil {
    private LeiosEraUtil() {
    }

    public static Era fromTxEraIndex(int txEraIndex) {
        return switch (txEraIndex) {
            case 0 -> Era.Byron;
            case 1 -> Era.Shelley;
            case 2 -> Era.Allegra;
            case 3 -> Era.Mary;
            case 4 -> Era.Alonzo;
            case 5 -> Era.Babbage;
            case 6 -> Era.Conway;
            case 7 -> Era.Dijkstra;
            default -> null;
        };
    }
}
