package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class Tip {
    private Point point;
    private long block;
}

