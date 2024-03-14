package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;

@Slf4j
public class TCPNodeServer extends NodeClient {

    private final ServerSocket serverSocket;

    public TCPNodeServer(int port, HandshakeAgent handshakeAgent, Agent... agents) {
        super(handshakeAgent, agents);
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        } catch (IOException e) {
            log.info("Error while accepting connection", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    protected SocketAddress createSocketAddress() {
        return null;
    }

    @Override
    protected EventLoopGroup configureEventLoopGroup() {
        return null;
    }

    @Override
    protected Class getChannelClass() {
        return null;
    }

    @Override
    protected void configureChannel(Bootstrap bootstrap) {

    }

    public void startAcceptListener() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    var socket = serverSocket.accept();
                    log.info("accpted connection");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        new Thread(r).start();
    }

}
