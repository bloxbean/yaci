package com.bloxbean.cardano.yaci.node.api.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a single upstream peer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpstreamConfig {
    private String host;
    private int port;
    @Builder.Default
    private PeerType type = PeerType.CARDANO;

    /**
     * Generate a unique peer ID for this upstream.
     */
    public String peerId() {
        return host + ":" + port;
    }
}
