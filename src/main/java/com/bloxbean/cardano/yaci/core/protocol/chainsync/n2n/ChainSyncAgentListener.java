package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronHead;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;

public class ChainSyncAgentListener implements AgentListener {

    public void intersactFound(Tip tip, Point point) {

    }

    public void intersactNotFound(Tip tip) {

    }

    public void rollforward(Tip tip, BlockHeader blockHeader) {

    }

    public void rollbackward(Tip tip, Point toPoint) {

    }

    public void rollforwardByronEra(Tip tip, ByronHead byronHead) {

    }
}
