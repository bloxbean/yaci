package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class Update {
    private Map<String, ProtocolParamUpdate> protocolParamUpdates;
    private long epoch;
}
