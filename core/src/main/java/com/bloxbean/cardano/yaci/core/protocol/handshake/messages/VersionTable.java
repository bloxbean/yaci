package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import lombok.*;

import java.util.HashMap;
import java.util.Map;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class VersionTable implements Message {
    private Map<Long, VersionData> versionDataMap = new HashMap<>();

    public void add(long protocol, VersionData versionData) {
        versionDataMap.put(protocol, versionData);
    }
}
