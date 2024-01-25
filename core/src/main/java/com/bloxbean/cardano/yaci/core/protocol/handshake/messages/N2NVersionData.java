package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class N2NVersionData extends VersionData {
    private Boolean initiatorAndResponderDiffusionMode;
    private Integer peerSharing = 0;
    private Boolean query = Boolean.FALSE;

    public N2NVersionData(long networkMagic, Boolean initiatorAndResponderDiffusionMode) {
        super(networkMagic);
        this.initiatorAndResponderDiffusionMode = initiatorAndResponderDiffusionMode;
    }

    public N2NVersionData(long networkMagic, Boolean initiatorAndResponderDiffusionMode, Integer peerSharing, Boolean query) {
        super(networkMagic);
        this.initiatorAndResponderDiffusionMode = initiatorAndResponderDiffusionMode;
        this.peerSharing = peerSharing;
        this.query = query;
    }
}
