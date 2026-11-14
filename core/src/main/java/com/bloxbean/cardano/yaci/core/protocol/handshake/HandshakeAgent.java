package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.*;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState.Propose;

@Slf4j
public class HandshakeAgent extends Agent<HandshakeAgentListener> {

    private final VersionTable versionTable;
    // The protocol state reaches Done before processResponse validates the peer.
    private volatile boolean handshakeFinished;
    private boolean suppressConnectionInfoLog = false;

    private ProposedVersions proposedVersions;

    public HandshakeAgent(VersionTable versionTable) {
        this(versionTable,true);
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
        switch ((HandshkeState)currentState) {
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

            N2NVersionData requestedVersionData = (N2NVersionData) versionDataMap.get(highestVersion);
            N2NVersionData supportedVersionData = (N2NVersionData) versionTable.getVersionDataMap().get(highestVersion);

            var acceptedVersionData = new N2NVersionData(
                    requestedVersionData.getNetworkMagic(),
                    requestedVersionData.getInitiatorOnlyDiffusionMode(),
                    negotiatedPeerSharing(requestedVersionData, supportedVersionData),
                    Boolean.TRUE.equals(requestedVersionData.getQuery())
                            && Boolean.TRUE.equals(supportedVersionData.getQuery()));

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

    private int negotiatedPeerSharing(N2NVersionData requestedVersionData, N2NVersionData supportedVersionData) {
        int requested = requestedVersionData.getPeerSharing() != null ? requestedVersionData.getPeerSharing() : 0;
        int supported = supportedVersionData.getPeerSharing() != null ? supportedVersionData.getPeerSharing() : 0;
        return Math.min(requested, supported);
    }

    @Override
    public synchronized void sendRequest(Message message) {
        super.sendRequest(message);
        if (message instanceof AcceptVersion acceptVersion) {
            setProtocolVersion(acceptVersion);
            handshakeOk();
        } else if (message instanceof Refuse refuse) {
            setProtocolVersion(null);
            handshakeError(refuse.getReason());
        }
    }

    @Override
    public void processResponse(Message message) {
        if (isAppOnly() && (handshakeFinished || message == null
                || !(message instanceof ProposedVersions || message instanceof AcceptVersion
                        || message instanceof Reason || message instanceof Refuse)
                || (isClient() && message instanceof ProposedVersions))) {
            setProtocolVersion(null);
            handshakeError(new ReasonRefused(N2NVersionTableConstant.PROTOCOL_V101,
                    "Unexpected app-only handshake response"));
            return;
        }
        if (message == null) return;
        if (message instanceof ProposedVersions) {
            this.proposedVersions = (ProposedVersions) message;
        } else if (message instanceof AcceptVersion accepted) {
            if (isAppOnly()) {
                VersionData offered = versionTable.getVersionDataMap().get(accepted.getVersionNumber());
                if (!isClient() || currentState != HandshkeState.Done || offered == null
                        || !(accepted.getVersionData() instanceof N2NVersionData)
                        || offered.getNetworkMagic() != accepted.getVersionData().getNetworkMagic()) {
                    setProtocolVersion(null);
                    handshakeError(new ReasonRefused(accepted.getVersionNumber(),
                            "Unsolicited app-only version or network magic"));
                    return;
                }
            }
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
        handshakeFinished = true;
        getAgentListeners().forEach(handshakeAgentListener -> handshakeAgentListener.handshakeOk());
    }

    private void handshakeError(Message message) {
        if (message instanceof Refuse refuse) {
            handshakeError(refuse.getReason());
        } else {
            handshakeError((Reason) message);
        }
    }

    private void handshakeError(Reason reason) {
        handshakeFinished = true;
        Channel failedChannel = isAppOnly() ? getChannel() : null;
        try {
            getAgentListeners().forEach(handshakeAgentListener -> handshakeAgentListener.handshakeError(reason));
        } finally {
            if (failedChannel != null) {
                failedChannel.close();
            }
        }
    }

    /** Whether this agent negotiates the exclusive app-only capability. */
    public boolean isAppOnly() {
        return N2NVersionTableConstant.isAppOnly(versionTable);
    }

    /** A completed negotiation with a validated accepted version, rather than a refusal. */
    public boolean isHandshakeSuccessful() {
        return handshakeFinished && getProtocolVersion() != null;
    }

    @Override
    public boolean isDone() {
        return isAppOnly() ? handshakeFinished : currentState == HandshkeState.Done;
    }

    public void reset() {
        if (isAppOnly()) {
            handshakeFinished = false;
            setProtocolVersion(null);
        }
        this.currentState = Propose;
    }

    public void setSuppressConnectionInfoLog(boolean suppressConnectionInfoLog) {
        this.suppressConnectionInfoLog = suppressConnectionInfoLog;
    }

    public boolean isSuppressConnectionInfoLog() {
        return suppressConnectionInfoLog;
    }
}
