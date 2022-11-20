package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class N2NVersionData extends VersionData {
    private boolean initiatorAndResponderDiffusionMode;

    public N2NVersionData(long networkMagic, boolean initiatorAndResponderDiffusionMode) {
        super(networkMagic);
        this.initiatorAndResponderDiffusionMode = initiatorAndResponderDiffusionMode;
    }
}
