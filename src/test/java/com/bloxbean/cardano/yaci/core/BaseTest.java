package com.bloxbean.cardano.yaci.core;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public class BaseTest {
    protected String node = "192.168.0.228";
    protected int nodePort = 6000;
    protected Point knownPoint = new Point(18014387, "9914c8da22a833a777d8fc1f735d2dbba70b99f15d765b6c6ee45fe322d92d93");

    protected String nodeSocketFile = "/Users/satya/work/cardano-node/preview/db/node.socket";
}
