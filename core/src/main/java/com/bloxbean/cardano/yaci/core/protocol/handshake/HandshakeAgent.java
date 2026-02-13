package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState.Propose;

@Slf4j
public class HandshakeAgent extends Agent<HandshakeAgentListener> {

    private final VersionTable versionTable;
    private boolean suppressConnectionInfoLog = false;

    private ProposedVersions proposedVersions;

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
            case Confirm:
                return prepareConfirmMessage();
            default:
                return null;
        }
    }

    private Message prepareConfirmMessage() {
        var versionDataMap = proposedVersions.getVersionTable().getVersionDataMap();

        List<Long> supportedProtocolVersions = new ArrayList<>();
        for (var entry : versionDataMap.entrySet()) {
            long proposedVersion = entry.getKey();
            if (versionTable.getVersionDataMap().containsKey(proposedVersion) &&
                versionTable.getVersionDataMap().get(proposedVersion).getNetworkMagic() ==
                entry.getValue().getNetworkMagic()) {
                supportedProtocolVersions.add(proposedVersion);
            }
        }

        if (supportedProtocolVersions.size() > 0) {
            supportedProtocolVersions.sort(Long::compareTo);
            long highestVersion = supportedProtocolVersions.get(supportedProtocolVersions.size() - 1);

            var acceptedVersionData = versionDataMap.get(highestVersion);
            // Accept the highest version
            var acceptVersion = new AcceptVersion(highestVersion, acceptedVersionData);
            System.out.println("Accept Version constructed !!!");
            return acceptVersion;
        } else {
            var versions = proposedVersions.getVersionTable().getVersionDataMap().keySet()
                    .stream().collect(Collectors.toList());

            Reason reason = new ReasonVersionMismatch(versions);
            Refuse refuse = new Refuse(reason);

            return refuse;
        }

    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;
        if (message instanceof ProposedVersions) {
            this.proposedVersions = (ProposedVersions) message;
        } else if (message instanceof AcceptVersion) {
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
