package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.*;

@Getter
@EqualsAndHashCode
@ToString
public class N2CVersionData extends VersionData {
    public N2CVersionData(long networkMagic) {
        super(networkMagic);
    }

    @Override
    public String toString() {
        return "N2CVersionData{" +
                "networkMagic=" + networkMagic +
                '}';
    }
}
