package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@ToString
public class ReasonVersionMismatch extends Reason {
    private List<Long> versionNumbers;
}
