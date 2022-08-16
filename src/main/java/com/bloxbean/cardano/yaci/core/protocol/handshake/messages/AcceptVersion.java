package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import lombok.*;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class AcceptVersion implements Message {
    private long versionNumber;
    private VersionData versionData;
}
