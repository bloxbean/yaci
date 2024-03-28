package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ProposedVersions;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import lombok.extern.slf4j.Slf4j;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeState.Propose;

@Slf4j
public class HandshakeAgent extends Agent<HandshakeAgentListener> {
    private final VersionTable versionTable;

    public HandshakeAgent(VersionTable versionTable) {
        this(versionTable, true);
    }
    public HandshakeAgent(VersionTable versionTable, boolean isClient) {
        super(isClient);
        this.versionTable = versionTable;
        this.currentState = Propose;
    }

    @Override
    public int getProtocolId() {
        return 0;
    }

    @Override
    public Message buildNextMessage() {
        log.info("buildNextMessage");
        switch ((HandshakeState) currentState) {
            case Propose:
                log.info("propose");
                return new ProposedVersions(versionTable); //TODO
            case Confirm:
                log.info("confirm");
                var protocolVersion = this.getProtocolVersion();
                log.info("protocolVersion: {}", protocolVersion);
                return protocolVersion;
            default:
                log.info("default");
                log.info("currenState: {}", currentState);
                return null;
        }
    }

    @Override
    public void processResponse(Message message) {
        log.info("Response {}", message);
        if (message == null) return;
        if (message instanceof AcceptVersion) {
            log.info("Handshake Ok!!! {}", message);
            setProtocolVersion((AcceptVersion) message);
            handshakeOk();
        } else if (message instanceof VersionTable) {
            log.info("VersionTable received!!! {}", message);
            //TODO -- Will be implemented for N2N 11,12 / N2C 15,16 with query attribute
            throw new UnsupportedOperationException("msgQueryReply is not supported yet");
        } else if (message instanceof ProposedVersions) {
            log.info("ProposedVersions");
            var proposedVersion = (ProposedVersions) message;
            var version = proposedVersion.getVersionTable().getVersionDataMap().keySet().stream().max(Long::compareTo).orElse(0L);
            var versionData = proposedVersion.getVersionTable().getVersionDataMap().get(version);
            var acceptVersion = new AcceptVersion(version, versionData);
            log.info("protocolVersion: {}", acceptVersion);
            setProtocolVersion(acceptVersion);
            handshakeOk();
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
        getAgentListeners().forEach(handshakeAgentListener -> handshakeAgentListener.handshakeError((Reason) message));
    }

    @Override
    public boolean isDone() {
        return currentState == HandshakeState.Done;
    }

    public void reset() {
        this.currentState = Propose;
    }
}
