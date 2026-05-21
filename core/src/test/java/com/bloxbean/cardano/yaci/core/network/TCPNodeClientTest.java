package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TCPNodeClientTest {

    @Test
    void standardModeCreatesFreshJvmSelectedSocketAddressForEachAttempt() {
        InspectableTCPNodeClient client = new InspectableTCPNodeClient("127.0.0.1", 3001,
                NodeClientConfig.defaultConfig());

        SocketAddress first = client.socketAddressCandidates().get(0);
        SocketAddress second = client.socketAddressCandidates().get(0);

        assertEquals(first, second);
        assertNotSame(first, second);
    }

    @Test
    void standardModeDoesNotUseExplicitDnsRotationResolver() {
        InspectableTCPNodeClient client = new InspectableTCPNodeClient("missing.yaci.invalid", 3001,
                NodeClientConfig.defaultConfig());

        InetSocketAddress socketAddress = assertDoesNotThrow(() ->
                (InetSocketAddress) client.socketAddressCandidates().get(0));

        assertEquals("missing.yaci.invalid", socketAddress.getHostString());
        assertEquals(3001, socketAddress.getPort());
    }

    @Test
    void dnsRotatingModeUsesExplicitDnsResolver() {
        NodeClientConfig config = NodeClientConfig.builder()
                .socketAddressResolutionMode(SocketAddressResolutionMode.DNS_ROTATING)
                .build();
        InspectableTCPNodeClient client = new InspectableTCPNodeClient("missing.yaci.invalid", 3001, config);

        assertThrows(IllegalStateException.class, client::socketAddressCandidates);
    }

    @Test
    void dnsRotatingWithIpv4OnlyExcludesIpv6Addresses() throws UnknownHostException {
        TCPNodeClient.DnsRotatingSocketAddressProvider provider = provider(SocketAddressFamily.IPV4_ONLY,
                address("2001:db8::1"), address("192.0.2.1"), address("198.51.100.1"));

        List<SocketAddress> socketAddresses = provider.get();

        assertEquals(2, socketAddresses.size());
        for (SocketAddress socketAddress : socketAddresses) {
            assertTrue(((InetSocketAddress) socketAddress).getAddress() instanceof Inet4Address);
        }
    }

    @Test
    void dnsRotatingWithIpv4PreferredOrdersIpv4BeforeIpv6() throws UnknownHostException {
        TCPNodeClient.DnsRotatingSocketAddressProvider provider = provider(SocketAddressFamily.IPV4_PREFERRED,
                address("2001:db8::1"), address("192.0.2.1"), address("2001:db8::2"), address("198.51.100.1"));

        List<SocketAddress> socketAddresses = provider.get();

        boolean seenIpv6 = false;
        for (SocketAddress socketAddress : socketAddresses) {
            InetAddress address = ((InetSocketAddress) socketAddress).getAddress();
            if (address instanceof Inet6Address) {
                seenIpv6 = true;
            } else {
                assertTrue(address instanceof Inet4Address);
                assertTrue(!seenIpv6, "IPv4 address should not appear after IPv6 in IPV4_PREFERRED mode");
            }
        }
    }

    @Test
    void dnsRotatingWithIpv4PreferredFallsBackToIpv6WhenNoIpv4Exists() throws UnknownHostException {
        TCPNodeClient.DnsRotatingSocketAddressProvider provider = provider(SocketAddressFamily.IPV4_PREFERRED,
                address("2001:db8::1"), address("2001:db8::2"));

        List<SocketAddress> socketAddresses = provider.get();

        assertEquals(2, socketAddresses.size());
        for (SocketAddress socketAddress : socketAddresses) {
            assertTrue(((InetSocketAddress) socketAddress).getAddress() instanceof Inet6Address);
        }
    }

    @Test
    void dnsRotatingWithIpv4OnlyFailsWhenNoIpv4Exists() throws UnknownHostException {
        TCPNodeClient.DnsRotatingSocketAddressProvider provider = provider(SocketAddressFamily.IPV4_ONLY,
                address("2001:db8::1"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, provider::get);

        assertTrue(exception.getMessage().contains("IPV4_ONLY"));
    }

    private static class InspectableTCPNodeClient extends TCPNodeClient {
        private InspectableTCPNodeClient(String host, int port, NodeClientConfig config) {
            super(host, port, config, new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(0)));
        }

        private List<SocketAddress> socketAddressCandidates() {
            return createSocketAddressCandidates();
        }
    }

    private TCPNodeClient.DnsRotatingSocketAddressProvider provider(SocketAddressFamily socketAddressFamily,
                                                                   InetAddress... addresses) {
        return new TCPNodeClient.DnsRotatingSocketAddressProvider("relay.example", 3001, socketAddressFamily,
                host -> addresses);
    }

    private InetAddress address(String address) throws UnknownHostException {
        return InetAddress.getByName(address);
    }
}
