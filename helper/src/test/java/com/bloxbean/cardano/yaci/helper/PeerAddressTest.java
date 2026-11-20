package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class PeerAddressTest {

    @Test
    void theMainConnectionsRemoteAddressWins() throws Exception {
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 7}), 3001);

        assertThat(PeerAddress.connected(agentConnectedTo(remote))).isEqualTo(new PeerAddress("10.0.0.7", 3001));
        assertThat(new PeerAddress.Tracker("relays.example", 3002).current(agentConnectedTo(remote)))
                .isEqualTo(new PeerAddress("10.0.0.7", 3001));
    }

    @Test
    void whileTheMainConnectionReconnectsItsLastAddressIsKept() throws Exception {
        PeerAddress.Tracker tracker = new PeerAddress.Tracker("relays.example", 3001);
        assertThat(tracker.current(agentConnectedTo(null))).isEqualTo(new PeerAddress("relays.example", 3001));

        InetSocketAddress first = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 7}), 3001);
        assertThat(tracker.current(agentConnectedTo(first))).isEqualTo(new PeerAddress("10.0.0.7", 3001));
        //down between attempts: the gap does not move the side connections to the host name
        assertThat(tracker.current(agentConnectedTo(null))).isEqualTo(new PeerAddress("10.0.0.7", 3001));

        InetSocketAddress next = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 8}), 3001);
        assertThat(tracker.current(agentConnectedTo(next))).isEqualTo(new PeerAddress("10.0.0.8", 3001));
    }

    @Test
    void withoutAnOpenConnectionThereIsNoConnectedAddress() {
        assertThat(PeerAddress.connected(null)).isNull();
        assertThat(PeerAddress.connected(agentConnectedTo(null))).isNull();
        assertThat(PeerAddress.connected(agentConnectedTo(InetSocketAddress.createUnresolved("other", 1)))).isNull();
    }

    private static KeepAliveAgent agentConnectedTo(SocketAddress remote) {
        return new KeepAliveAgent() {
            @Override
            public SocketAddress getRemoteAddress() {
                return remote;
            }
        };
    }
}
