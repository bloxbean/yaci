package com.bloxbean.cardano.yaci.core.protocol.localstate;

import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquire;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquired;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgRelease;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** released() reports a release on both sides: a client once its MsgRelease is sent, a responder once it arrives. */
class LocalStateQueryReleaseTest {

    private static AtomicInteger countReleases(LocalStateQueryAgent agent) {
        AtomicInteger released = new AtomicInteger();
        agent.addListener(new LocalStateQueryListener() {
            @Override
            public void released() {
                released.incrementAndGet();
            }
        });
        return released;
    }

    @Test
    void aClientReportsItsReleaseOnceSent() {
        LocalStateQueryAgent client = new LocalStateQueryAgent(true);
        AtomicInteger released = countReleases(client);
        client.sendRequest(new MsgAcquire(null));
        client.receiveResponse(new MsgAcquired());

        client.sendRequest(new MsgRelease());

        assertThat(client.getCurrentState()).isEqualTo(LocalStateQueryState.Idle);
        assertThat(released).hasValue(1);
    }

    @Test
    void aResponderReportsTheReleaseItReceives() {
        LocalStateQueryAgent responder = new LocalStateQueryAgent(false);
        AtomicInteger released = countReleases(responder);
        responder.receiveResponse(new MsgAcquire(null));
        responder.sendRequest(new MsgAcquired());

        responder.receiveResponse(new MsgRelease());

        assertThat(responder.getCurrentState()).isEqualTo(LocalStateQueryState.Idle);
        assertThat(released).hasValue(1);
    }
}
