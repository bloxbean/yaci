package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public class BaseTest {
    protected String node = Constants.PREPROD_PUBLIC_RELAY_ADDR;
    protected int nodePort = Constants.PREPROD_PUBLIC_RELAY_PORT;
    protected long protocolMagic = Constants.PREPROD_PROTOCOL_MAGIC;
    protected Point knownPoint = new Point(13003663, "b896e43a25de269cfc47be7afbcbf00cad41a5011725c2732393f1b4508cf41d");

    protected String nodeSocketFile = "/Users/satya/work/cardano-node/preprod/db/node.socket";

    protected String previewNode = Constants.PREVIEW_PUBLIC_RELAY_ADDR;
    protected int previewNodePot = Constants.PREVIEW_PUBLIC_RELAY_PORT;
    protected long previewProtocolMagic = Constants.PREVIEW_PROTOCOL_MAGIC;
    protected Point previewKnownPoint = new Point(2000, "5659572d8014080f38efe74c8fe9ab7c1195bac6e64c24bf0a2d41fafe87e8f8");

    protected String previewNodeSocketFile = "/Users/satya/work/cardano-node/preview/db/node.socket";
}
