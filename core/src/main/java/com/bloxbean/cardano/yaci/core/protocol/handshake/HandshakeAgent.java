package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ProposedVersions;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState.Propose;

@Slf4j
public class HandshakeAgent extends Agent<HandshakeAgentListener> {
    private final VersionTable versionTable;
    private boolean suppressConnectionInfoLog = false;

    public HandshakeAgent(VersionTable versionTable) {
        this(versionTable,true);
    }
    public HandshakeAgent(VersionTable versionTable, boolean isClient) {
        super(isClient);
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
            if (log.isDebugEnabled() || !suppressConnectionInfoLog)
                log.info("Handshake Ok!!! {}", message);
            setProtocolVersion((AcceptVersion)message);
            handshakeOk();
        } else if (message instanceof VersionTable) {
            log.info("VersionTable received!!! {}", message);
            //TODO -- Will be implemented for N2N 11,12 / N2C 15,16 with query attribute
            throw new UnsupportedOperationException("msgQueryReply is not supported yet");
        } else {
            log.error("Handshake failed!!! {}", message);
            setProtocolVersion(null);
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

    public void setSuppressConnectionInfoLog(boolean suppressConnectionInfoLog) {
        this.suppressConnectionInfoLog = suppressConnectionInfoLog;
    }

    public boolean isSuppressConnectionInfoLog() {
        return suppressConnectionInfoLog;
    }
}
