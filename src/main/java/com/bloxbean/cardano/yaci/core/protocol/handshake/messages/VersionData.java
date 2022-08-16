package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.*;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class VersionData {
    private long networkMagic;
    private boolean initiatorAndResponderDiffusionMode;
}
