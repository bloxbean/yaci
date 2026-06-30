package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import io.netty.channel.Channel;

/**
 * Optional inbound server connection hook.
 *
 * <p>Implementations must return quickly and must not block on network or
 * protocol operations.</p>
 */
public interface ServerConnectionListener {
    default ServerConnectionDecision onAccept(Channel channel) {
        return ServerConnectionDecision.accept();
    }

    default void onHandshakeComplete(Channel channel, AcceptVersion acceptedVersion) {
    }

    default void onHandshakeFailed(Channel channel, Throwable cause) {
    }

    default void onClosed(Channel channel) {
    }
}
