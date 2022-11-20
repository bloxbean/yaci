package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@ToString
public class ReasonHandshakeDecodeError extends Reason {
    private long versionNumber;
    private String str;
}
