package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class N2NVersionData extends VersionData {
    private final Boolean initiatorOnlyDiffusionMode;
    private final Integer peerSharing;
    private final Boolean query;

    public N2NVersionData(long networkMagic, Boolean initiatorOnlyDiffusionMode) {
        super(networkMagic);
        this.peerSharing = 0;
        this.query = Boolean.FALSE;
        this.initiatorOnlyDiffusionMode = initiatorOnlyDiffusionMode;
    }

    public N2NVersionData(long networkMagic, Boolean initiatorOnlyDiffusionMode, Integer peerSharing, Boolean query) {
        super(networkMagic);
        this.initiatorOnlyDiffusionMode = initiatorOnlyDiffusionMode;
        this.peerSharing = peerSharing;
        this.query = query;
    }
}
