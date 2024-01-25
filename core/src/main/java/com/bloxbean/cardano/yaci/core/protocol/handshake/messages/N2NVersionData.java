package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class N2NVersionData extends VersionData {
    public static final boolean InitiatorOnlyDiffusionMode = true;
    public static final boolean InitiatorAndResponderDiffusionMode = false;

    private Boolean diffusionMode;
    private Integer peerSharing = 0;
    private Boolean query = Boolean.FALSE;

    public N2NVersionData(long networkMagic, Boolean diffusionMode) {
        super(networkMagic);
        this.diffusionMode = diffusionMode;
    }

    public N2NVersionData(long networkMagic, Boolean diffusionMode, Integer peerSharing, Boolean query) {
        super(networkMagic);
        this.diffusionMode = diffusionMode;
        this.peerSharing = peerSharing;
        this.query = query;
    }
}
