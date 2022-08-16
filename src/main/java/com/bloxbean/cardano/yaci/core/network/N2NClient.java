package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoClientInboundHandler;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class N2NClient {
    private String host;
    private int port;

    public N2NClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public Disposable start(HandshakeAgent handshakeAgent, Agent... agents) {
        try {
            Bootstrap b = new Bootstrap();
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);

            List<Agent> allAgents = Arrays.stream(agents).collect(Collectors.toList());
            allAgents.add(0, handshakeAgent);

            b.handler(new ChannelInitializer<SocketChannel>() {

                @Override
                public void initChannel(SocketChannel ch)
                        throws Exception {
                    ch.pipeline().addLast(new MiniProtoRequestDataEncoder(),
                            new MiniProtoStreamingByteToMessageDecoder(agents),
                            new MiniProtoClientInboundHandler(handshakeAgent, agents));
                }
            });

            SocketAddress socketAddress = null;
            socketAddress = new InetSocketAddress(host, port);

            Session session = new Session(socketAddress, b, workerGroup, handshakeAgent, agents);
            Disposable shutdownHook = session.start();
            return shutdownHook;
        } catch (Exception e) {
            log.error("Error", e);
            throw new RuntimeException(e);
        }
    }
}
