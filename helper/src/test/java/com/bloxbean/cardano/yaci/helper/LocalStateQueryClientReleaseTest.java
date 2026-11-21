package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryAgent;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryState;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquire;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquired;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgRelease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** release(): one MsgRelease on the wire however many callers, each caller ends, and an acquire after it runs. */
class LocalStateQueryClientReleaseTest {

    /** An agent whose writes complete only when the test says so, as Agent.sendNextMessage's callback would. */
    static final class HeldWritesAgent extends LocalStateQueryAgent {
        final List<Message> written = new ArrayList<>();
        private final List<Message> pending = new ArrayList<>();
        boolean connected = true;

        @Override
        public boolean isChannelActive() {
            return connected;
        }

        @Override
        public void sendNextMessage() {
            if (hasAgency()) {
                Message message = buildNextMessage();
                if (message != null) {
                    written.add(message);
                    pending.add(message);
                }
            }
        }

        void completeWrites() {
            List<Message> done = new ArrayList<>(pending);
            pending.clear();
            done.forEach(this::sendRequest);
        }
    }

    private HeldWritesAgent agent;
    private LocalStateQueryClient client;

    @BeforeEach
    void acquired() {
        agent = new HeldWritesAgent();
        client = new LocalStateQueryClient(agent);
        agent.sendRequest(new MsgAcquire(null));
        agent.receiveResponse(new MsgAcquired());
    }

    @Test
    void overlappingReleasesShareOneMsgRelease() {
        CompletableFuture<Void> first = client.release().toFuture();
        CompletableFuture<Void> second = client.release().toFuture();
        assertThat(agent.written).hasSize(1).first().isInstanceOf(MsgRelease.class);
        assertThat(first).isNotDone();

        agent.completeWrites();

        assertThat(first).isCompletedWithValue(null);
        assertThat(second).isCompletedWithValue(null);
        assertThat(agent.written).hasSize(1);
        assertThat(agent.getCurrentState()).isEqualTo(LocalStateQueryState.Idle);
    }

    @Test
    void aReleaseWithoutAConnectionFails() {
        agent.connected = false;

        CompletableFuture<Void> release = client.release().toFuture();

        assertThat(release).isCompletedExceptionally();
        assertThat(agent.written).isEmpty();
    }

    @Test
    void anAcquireChainedAfterReleaseAcquiresAgain() {
        client.release().then(client.acquire()).toFuture();
        assertThat(agent.written).hasSize(1).first().isInstanceOf(MsgRelease.class);

        agent.completeWrites();

        // decided once the release has moved the agent to Idle
        assertThat(agent.written).hasSize(2).last().isInstanceOf(MsgAcquire.class);
        agent.completeWrites();
        assertThat(agent.getCurrentState()).isEqualTo(LocalStateQueryState.Acquiring);
    }
}
