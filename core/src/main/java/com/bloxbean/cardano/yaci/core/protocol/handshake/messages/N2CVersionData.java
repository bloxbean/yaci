package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class N2CVersionData extends VersionData {
    protected boolean query;
    public N2CVersionData(long networkMagic, boolean query) {
        super(networkMagic);
        this.query = query;
    }

    @Override
    public String toString() {
        return "N2CVersionData{" +
                "networkMagic=" + networkMagic +
                ", query=" + query +
                '}';
    }
}
