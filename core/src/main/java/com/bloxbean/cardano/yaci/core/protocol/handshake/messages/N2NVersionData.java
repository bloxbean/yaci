package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class N2NVersionData extends VersionData {
    private Boolean initiatorOnlyDiffusionMode;
    private Integer peerSharing = 0;
    private Boolean query = Boolean.FALSE;

    public N2NVersionData(long networkMagic, Boolean initiatorOnlyDiffusionMode) {
        super(networkMagic);
        this.initiatorOnlyDiffusionMode = initiatorOnlyDiffusionMode;
    }

    public N2NVersionData(long networkMagic, Boolean initiatorOnlyDiffusionMode, Integer peerSharing, Boolean query) {
        super(networkMagic);
        this.initiatorOnlyDiffusionMode = initiatorOnlyDiffusionMode;
        this.peerSharing = peerSharing;
        this.query = query;
    }
}
