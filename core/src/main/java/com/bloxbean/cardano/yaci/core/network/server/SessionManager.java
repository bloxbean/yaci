package com.bloxbean.cardano.yaci.core.network.server;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SessionManager {
    private static final Set<Channel> activeClients = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void addClient(Channel channel) {
        activeClients.add(channel);
        log.info("Client connected: {} (Total: {})", channel.remoteAddress(), activeClients.size());
    }

    public static void removeClient(Channel channel) {
        activeClients.remove(channel);
        log.info("Client disconnected: {} (Remaining: {})", channel.remoteAddress(), activeClients.size());
    }

    public static int getActiveClientCount() {
        return activeClients.size();
    }
}
