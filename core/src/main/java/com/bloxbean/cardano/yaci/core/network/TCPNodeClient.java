package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is the main class to initialize single or multiple agents for Node-to-node mini-protocol and setup channel handlers to send / process
 * network bytes.
 */
@Slf4j
public class TCPNodeClient extends NodeClient {
    private String host;
    private int port;
    private DnsRotatingSocketAddressProvider socketAddressProvider;

    /**
     * Constructor with NodeClientConfig for configurable connection behavior.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param config the connection configuration
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public TCPNodeClient(String host, int port, NodeClientConfig config, HandshakeAgent handshakeAgent,
                         Agent... agents) {
        super(config, handshakeAgent, agents);
        this.host = host;
        this.port = port;
        this.socketAddressProvider = new DnsRotatingSocketAddressProvider(host, port,
                getConfig().getSocketAddressFamily());
    }

    /**
     * Constructor with default configuration (for backward compatibility).
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param handshakeAgent the handshake agent
     * @param agents the protocol agents
     */
    public TCPNodeClient(String host, int port, HandshakeAgent handshakeAgent, Agent... agents) {
        this(host, port, NodeClientConfig.defaultConfig(), handshakeAgent, agents);
    }

    @Override
    protected SocketAddress createSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    @Override
    protected List<SocketAddress> createSocketAddressCandidates() {
        if (getConfig().getSocketAddressResolutionMode() == SocketAddressResolutionMode.DNS_ROTATING)
            return socketAddressProvider.get();

        return super.createSocketAddressCandidates();
    }

    @Override
    protected EventLoopGroup configureEventLoopGroup() {
        return new NioEventLoopGroup();
    }

    @Override
    protected Class getChannelClass() {
        return NioSocketChannel.class;
    }

    @Override
    protected void configureChannel(Bootstrap bootstrap) {
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getConfig().getConnectionTimeoutMs());
        if (getConfig().hasLocalBindAddress()) {
            bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        }
    }

    static class DnsRotatingSocketAddressProvider {
        private final String host;
        private final int port;
        private final SocketAddressFamily socketAddressFamily;
        private final InetAddressResolver addressResolver;
        private final AtomicInteger cursor = new AtomicInteger(ThreadLocalRandom.current().nextInt());

        DnsRotatingSocketAddressProvider(String host, int port, SocketAddressFamily socketAddressFamily) {
            this(host, port, socketAddressFamily, InetAddress::getAllByName);
        }

        DnsRotatingSocketAddressProvider(String host, int port, SocketAddressFamily socketAddressFamily,
                                         InetAddressResolver addressResolver) {
            this.host = host;
            this.port = port;
            this.socketAddressFamily = socketAddressFamily != null ? socketAddressFamily : SocketAddressFamily.ANY;
            this.addressResolver = addressResolver;
        }

        public List<SocketAddress> get() {
            try {
                List<InetAddress> addresses = selectAddresses(Arrays.asList(addressResolver.resolve(host)));
                if (addresses.isEmpty()) {
                    throw new IllegalStateException("Unable to resolve " + host + ":" + port
                            + " with address family " + socketAddressFamily);
                }

                List<SocketAddress> socketAddresses = new ArrayList<>(addresses.size());
                for (InetAddress address : addresses) {
                    socketAddresses.add(new InetSocketAddress(address, port));
                }
                return socketAddresses;
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Unable to resolve " + host + ":" + port, e);
            }
        }

        private List<InetAddress> selectAddresses(List<InetAddress> addresses) {
            List<InetAddress> ipv4 = filterByFamily(addresses, Inet4Address.class);
            List<InetAddress> ipv6 = filterByFamily(addresses, Inet6Address.class);
            int start = cursor.getAndIncrement();

            return switch (socketAddressFamily) {
                case ANY -> rotate(addresses, start);
                case IPV4_ONLY -> rotate(ipv4, start);
                case IPV6_ONLY -> rotate(ipv6, start);
                case IPV4_PREFERRED -> concatenate(rotate(ipv4, start), rotate(ipv6, start));
                case IPV6_PREFERRED -> concatenate(rotate(ipv6, start), rotate(ipv4, start));
            };
        }

        private <T extends InetAddress> List<InetAddress> filterByFamily(List<InetAddress> addresses,
                                                                         Class<T> addressFamilyClass) {
            List<InetAddress> filteredAddresses = new ArrayList<>();
            for (InetAddress address : addresses) {
                if (addressFamilyClass.isInstance(address)) {
                    filteredAddresses.add(address);
                }
            }
            return filteredAddresses;
        }

        private List<InetAddress> rotate(List<InetAddress> addresses, int start) {
            if (addresses.size() <= 1)
                return new ArrayList<>(addresses);

            int index = Math.floorMod(start, addresses.size());
            List<InetAddress> rotatedAddresses = new ArrayList<>(addresses.size());
            rotatedAddresses.addAll(addresses.subList(index, addresses.size()));
            rotatedAddresses.addAll(addresses.subList(0, index));
            return rotatedAddresses;
        }

        private List<InetAddress> concatenate(List<InetAddress> first, List<InetAddress> second) {
            List<InetAddress> addresses = new ArrayList<>(first.size() + second.size());
            addresses.addAll(first);
            addresses.addAll(second);
            return addresses;
        }
    }

    interface InetAddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
