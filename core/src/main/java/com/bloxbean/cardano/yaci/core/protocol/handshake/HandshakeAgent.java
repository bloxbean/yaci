package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.*;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState.Propose;

@Slf4j
public class HandshakeAgent extends Agent<HandshakeAgentListener> {
    private final VersionTable versionTable;

    public HandshakeAgent(VersionTable versionTable) {
        this.versionTable = versionTable;
        this.currenState = Propose;
    }

    @Override
    public int getProtocolId() {
        return 0;
    }

    @Override
    public Message buildNextMessage() {
        switch ((HandshkeState)currenState) {
            case Propose:
                return new ProposedVersions(versionTable); //TODO
            default:
                return null;
        }
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;
        if (message instanceof AcceptVersion) {
            log.info("Handshake Ok!!! {}", message);
            setAcceptedVersion((AcceptVersion)message);
            handshakeOk();
        } else {
            log.error("Handshake failed!!! {}", message);
            setAcceptedVersion(null);
            handshakeError(message);
        }
    }

    private void handshakeOk() {
        getAgentListeners().forEach(handshakeAgentListener -> handshakeAgentListener.handshakeOk());
    }

    private void handshakeError(Message message) {
        getAgentListeners().forEach(handshakeAgentListener -> handshakeAgentListener.handshakeError((Reason)message));
    }

    @Override
    public boolean isDone() {
        return currenState == HandshkeState.Done;
    }

    public void reset() {
        this.currenState = Propose;
    }
}
