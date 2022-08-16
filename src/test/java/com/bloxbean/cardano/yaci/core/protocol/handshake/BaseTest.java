package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public class BaseTest {
    protected String node = "relays-new.cardano-testnet.iohkdev.io";
    protected int nodePort = 3001;
    protected Point knownPoint = new Point(18014387, "9914c8da22a833a777d8fc1f735d2dbba70b99f15d765b6c6ee45fe322d92d93");
}
