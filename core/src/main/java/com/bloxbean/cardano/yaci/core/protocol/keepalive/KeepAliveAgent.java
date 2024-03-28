package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgDone;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveState.Client;
import static com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveState.Done;

@Slf4j
public class KeepAliveAgent extends Agent<KeepAliveListener> {
    public static final int MAX_NUM = 65535;
    private boolean shutDown;
    private Queue<MsgKeepAlive> reqQueue;

    public KeepAliveAgent() {
        this(true);
    }
    public KeepAliveAgent(boolean isClient) {
        super(isClient);
        this.currentState = Client;
        this.reqQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public int getProtocolId() {
       return 8;
    }

    @Override
    public boolean isDone() {
        return currentState == Done;
    }

    @Override
    protected Message buildNextMessage() {
        log.info("buildNextMessage");
        if (shutDown)
            return new MsgDone();

        switch ((KeepAliveState) currentState) {
            case Client:
                if (reqQueue.peek() != null) {
                    return reqQueue.poll();
                } else {
                    int random = new Random().nextInt(MAX_NUM - 0 + 1) + 0;
                    return new MsgKeepAlive(random);
                }
            default:
                return null;
        }
    }

    @Override
    protected void processResponse(Message message) {
        log.info("MsgKeepAliveResponse: {}", message);
        if (message == null) return;
        if (message instanceof MsgKeepAliveResponse) {
            log.info("MsgKeepAliveResponse: {}", message);
            handleKeepAliveResponse((MsgKeepAliveResponse) message);
        } else {
            if (message instanceof MsgKeepAlive) {
                log.info("//TODO We should not receive MsgKeepAlive message. {}", message);
            } else
                log.error("Invalid message !!! {}", message);
        }
    }

    private void handleKeepAliveResponse(MsgKeepAliveResponse keepAliveResponse) {
        getAgentListeners().stream().forEach(
                listener -> listener.keepAliveResponse(keepAliveResponse)
        );
    }

    @Override
    public void shutdown() {
        this.shutDown = true;
    }

    @Override
    public void reset() {
        this.currentState = Client;
        reqQueue.clear();
    }

    public void sendKeepAlive(int cookie) {
        if (cookie > MAX_NUM)
            throw new IllegalArgumentException("Cookie value can be between 0 and " + MAX_NUM);
        reqQueue.add(new MsgKeepAlive(cookie));
        sendNextMessage();
    }
}
