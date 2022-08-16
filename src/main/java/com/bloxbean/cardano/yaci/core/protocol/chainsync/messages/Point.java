package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class Point {
    public final static Point ORIGIN = new Point();
    private long slot;
    private String hash;

}


