package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.*;

@Getter
@EqualsAndHashCode
@ToString
public class OldN2CVersionData extends VersionData {
    public OldN2CVersionData(long networkMagic) {
        super(networkMagic);
    }

    @Override
    public String toString() {
        return "N2CVersionData{" +
                "networkMagic=" + networkMagic +
                '}';
    }
}
