package com.bloxbean.cardano.yaci.core.protocol;

import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWriteLockTest {

    /**
     * A write that completes on the calling thread runs its success callback there. The callback takes the agent
     * lock, so it must not run while the channel lock is held: agent-locked writers take the locks in the opposite
     * order, and the two threads deadlock.
     */
    @Test
    void successCallbackRunsWithoutChannelLock() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicReference<Boolean> channelLockHeld = new AtomicReference<>();
        KeepAliveAgent agent = new KeepAliveAgent() {
            @Override
            public synchronized void sendRequest(Message message) {
                channelLockHeld.set(Thread.holdsLock(channel));
                super.sendRequest(message);
            }
        };
        agent.setChannel((Channel) channel);

        agent.sendNextMessage();

        assertThat(channel.<Object>readOutbound()).isInstanceOf(Segment.class);
        assertThat(channelLockHeld.get()).isFalse();
        channel.finishAndReleaseAll();
    }
}
