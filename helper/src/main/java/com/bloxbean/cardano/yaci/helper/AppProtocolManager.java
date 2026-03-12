package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Manages app-layer protocols (Protocol 100+) for a peer connection.
 * The AppMsgSubmissionAgent is always created internally. Callers signal intent
 * via {@link #enableAppMsg()}; actual activation only happens if the peer also
 * negotiates V100 during handshake (two-level check).
 */
@Slf4j
public class AppProtocolManager {
    private final AppMsgSubmissionAgent appMsgAgent = new AppMsgSubmissionAgent();
    private boolean appMsgEnabled = false;

    /**
     * Signal intent to use the AppMsgSubmission protocol.
     * The agent is only activated after handshake if the peer also supports V100.
     */
    public void enableAppMsg() {
        this.appMsgEnabled = true;
    }

    public boolean isAppMsgEnabled() {
        return appMsgEnabled;
    }

    public Optional<AppMsgSubmissionAgent> getAppMsgSubmissionAgent() {
        return Optional.of(appMsgAgent);
    }

    /**
     * Get all app agents to include in the connection agent list.
     */
    public List<Agent<?>> getAgents() {
        return List.of(appMsgAgent);
    }

    /**
     * Wire listeners on the internal app agent. Called once during init.
     * The client inbound handler does NOT auto-call sendNextMessage(),
     * so the listener must trigger replies after each server request.
     */
    public void setupListeners() {
        appMsgAgent.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleRequestMessageIds(
                    com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessageIds request) {
                appMsgAgent.sendNextMessage();
            }

            @Override
            public void handleRequestMessages(
                    com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessages request) {
                appMsgAgent.sendNextMessage();
            }
        });
        log.info("AppMsgSubmission listener wired for internal app agent");
    }

    /**
     * Enable app protocols if the negotiated version supports them AND
     * the caller has signalled intent via {@link #enableAppMsg()}.
     * Called after handshake completes.
     * @param acceptVersion the negotiated version from handshake
     */
    public void onHandshakeComplete(AcceptVersion acceptVersion) {
        if (!appMsgEnabled) return;  // caller didn't enable it
        if (acceptVersion == null) return;
        if (!N2NVersionTableConstant.isAppLayerVersion(acceptVersion.getVersionNumber())) {
            log.info("Peer negotiated V{} — app-layer protocols not activated",
                    acceptVersion.getVersionNumber());
            return;
        }
        activateAppMsgSubmission();
    }

    private void activateAppMsgSubmission() {
        appMsgAgent.sendNextMessage();  // sends MsgInit
        log.info("AppMsgSubmission protocol activated (MsgInit sent)");
    }
}
