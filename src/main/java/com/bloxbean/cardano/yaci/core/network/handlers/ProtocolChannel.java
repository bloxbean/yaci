package com.bloxbean.cardano.yaci.core.network.handlers;

import lombok.Data;

@Data
public class ProtocolChannel {
    private byte[] bytes;

    public ProtocolChannel() {
        this.bytes = new byte[0];
    }
}
