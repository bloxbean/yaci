package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.IntersectFound;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Rollbackward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChainsyncAgentPipeliningTest {
    private static final int REQUEST_NEXT = 0;
    private static final int BATCH_SIZE = 100;

    @Test
    void rollbackward_releasesItsOutstandingRequest() {
        Point point = new Point(100, "aa".repeat(32));
        Tip tip = new Tip(new Point(200, "bb".repeat(32)), 200);
        var channel = new ChainSyncServerAgentConcurrencyTest.StubChannel();
        var agent = new ChainsyncAgent(new Point[]{point});
        agent.enablePipelining(true);
        agent.setChannel(channel);

        agent.sendNextMessage(); // FindIntersect
        agent.receiveResponse(new IntersectFound(point, tip));
        agent.sendNextMessage(); // full pipeline of RequestNext
        assertThat(channel.writtenMessageIds()).filteredOn(id -> id == REQUEST_NEXT).hasSize(BATCH_SIZE);

        // First reply after an intersection is a Rollbackward; it answers one RequestNext
        agent.receiveResponse(new Rollbackward(point, tip));
        agent.sendNextMessage();

        assertThat(channel.writtenMessageIds()).filteredOn(id -> id == REQUEST_NEXT).hasSize(BATCH_SIZE + 1);
    }
}
