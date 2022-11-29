package com.bloxbean.cardano.yaci.core;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public class BaseTest {
    protected String node = Constants.PREPROD_IOHK_RELAY_ADDR;
    protected int nodePort = Constants.PREPROD_IOHK_RELAY_PORT;
    protected long protocolMagic = Constants.PREPROD_PROTOCOL_MAGIC;
    protected Point knownPoint = new Point(13003663, "b896e43a25de269cfc47be7afbcbf00cad41a5011725c2732393f1b4508cf41d");

    protected String nodeSocketFile = "/Users/satya/work/cardano-node/prepod/db/node.socket";
}
