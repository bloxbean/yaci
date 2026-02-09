package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgDone;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveState.*;

/**
 * KeepAlive Server Agent - Handles client KeepAlive messages
 * This agent responds to client MsgKeepAlive messages with MsgKeepAliveResponse
 */
@Slf4j
public class KeepAliveServerAgent extends Agent<KeepAliveListener> {

    private boolean shutDown;
    private Message pendingResponse;

    public KeepAliveServerAgent() {
        super(false); // This is a server agent
        this.currentState = Client;
    }

    @Override
    public int getProtocolId() {
        return 8; // KeepAlive protocol ID
    }

    @Override
    public boolean isDone() {
        return currentState == Done;
    }

    @Override
    protected Message buildNextMessage() {
        if (shutDown) {
            return new MsgDone();
        }

        // Return pending response when server has agency
        if (pendingResponse != null) {
            Message response = pendingResponse;
            pendingResponse = null; // Clear after returning

            // Don't manually set state - let Agent.sendRequest handle it
            return response;
        }

        return null;
    }

    @Override
    protected void processResponse(Message message) {
        if (message == null) return;

        if (message instanceof MsgKeepAlive) {
            handleKeepAlive((MsgKeepAlive) message);
        } else if (message instanceof MsgDone) {
            handleDone();
        } else {
            log.warn("Unexpected message type received: {}", message.getClass().getSimpleName());
        }
    }

    private void handleKeepAlive(MsgKeepAlive keepAlive) {
        if (log.isDebugEnabled())
            log.debug("Received KeepAlive message with cookie: {}", keepAlive.getCookie());

        // Echo back the same cookie value
        MsgKeepAliveResponse response = new MsgKeepAliveResponse(keepAlive.getCookie());
        this.pendingResponse = response;

        if (log.isDebugEnabled())
            log.info("Sending KeepAliveResponse with cookie: {}", keepAlive.getCookie());

        // Notify listeners
        getAgentListeners().forEach(listener ->
            listener.keepAliveResponse(response));
    }

    private void handleDone() {
        log.debug("Received Done message, transitioning to Done state");
        this.currentState = Done;
        this.pendingResponse = new MsgDone();
    }

    @Override
    public void shutdown() {
        this.shutDown = true;
    }

    @Override
    public void reset() {
        this.currentState = Server;
        this.pendingResponse = null;
    }
}
