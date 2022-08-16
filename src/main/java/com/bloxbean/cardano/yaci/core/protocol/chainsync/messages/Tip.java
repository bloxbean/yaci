package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Tip {
    private Point point;
    private long block;
}

