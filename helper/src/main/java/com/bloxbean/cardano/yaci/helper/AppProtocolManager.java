package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages app-layer protocols (Protocol 100+) for a peer connection.
 * Callers signal intent via {@link #enableAppMsg()} and/or
 * {@link #enableAppChainSync()}; an agent is added to a connection only when
 * its protocol is enabled, and app-layer traffic flows only when the peer
 * negotiates an app-layer handshake version (V100+). The app-chain sync
 * client (protocol 103) is fully request-driven, so enabling it just rides
 * the agent on the connection — callers gate their requests on
 * {@link #isAppLayerNegotiated()}.
 */
@Slf4j
public class AppProtocolManager {
    private final AppMsgSubmissionConfig appMsgConfig;
    private final AppMsgSubmissionAgent appMsgAgent;
    private boolean appMsgEnabled = false;
    private boolean listenersSetup = false;
    private AppChainSyncClientAgent appChainSyncAgent;
    private boolean appChainSyncEnabled = false;
    private volatile boolean appLayerNegotiated = false;

    public AppProtocolManager() {
        this(AppMsgSubmissionConfig.createDefault());
    }

    /**
     * @param appMsgConfig transport config (chain-ids, size/TTL limits) shared by
     *                     the client agent on this connection
     */
    public AppProtocolManager(AppMsgSubmissionConfig appMsgConfig) {
        this.appMsgConfig = appMsgConfig != null ? appMsgConfig : AppMsgSubmissionConfig.createDefault();
        this.appMsgAgent = new AppMsgSubmissionAgent(this.appMsgConfig);
    }

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

    public AppMsgSubmissionAgent getAppMsgSubmissionAgent() {
        return appMsgAgent;
    }

    public AppMsgSubmissionConfig getAppMsgConfig() {
        return appMsgConfig;
    }

    /**
     * Signal intent to use the AppChainSync protocol (103, finalized app-block
     * range fetch). The agent rides the connection when enabled; it sends
     * nothing until {@link AppChainSyncClientAgent#requestRange} is called.
     */
    public void enableAppChainSync() {
        if (appChainSyncAgent == null)
            appChainSyncAgent = new AppChainSyncClientAgent();
        this.appChainSyncEnabled = true;
    }

    public boolean isAppChainSyncEnabled() {
        return appChainSyncEnabled;
    }

    /** Null until {@link #enableAppChainSync()} is called. */
    public AppChainSyncClientAgent getAppChainSyncAgent() {
        return appChainSyncAgent;
    }

    /**
     * True once the handshake completed with an app-layer version (V100+).
     * Callers should gate app-chain sync requests on this.
     */
    public boolean isAppLayerNegotiated() {
        return appLayerNegotiated;
    }

    /**
     * Get app agents to include in the connection agent list.
     * Returns an empty list unless at least one app protocol is enabled.
     */
    public List<Agent<?>> getAgents() {
        List<Agent<?>> agents = new ArrayList<>(2);
        if (appMsgEnabled)
            agents.add(appMsgAgent);
        if (appChainSyncEnabled)
            agents.add(appChainSyncAgent);
        return agents;
    }

    /**
     * Wire listeners on the internal app agent. Called once during init.
     * The client inbound handler does NOT auto-call sendNextMessage(),
     * so the listener must trigger replies after each server request.
     */
    public void setupListeners() {
        if (listenersSetup)
            return;

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
        listenersSetup = true;
        log.info("AppMsgSubmission listener wired for internal app agent");
    }

    /**
     * Enable app protocols if the negotiated version supports them AND
     * the caller has signalled intent via {@link #enableAppMsg()} /
     * {@link #enableAppChainSync()}. Called after handshake completes.
     * @param acceptVersion the negotiated version from handshake
     */
    public void onHandshakeComplete(AcceptVersion acceptVersion) {
        if (acceptVersion == null) return;
        if (!appMsgEnabled && !appChainSyncEnabled) return;  // caller didn't enable anything
        if (!N2NVersionTableConstant.isAppLayerVersion(acceptVersion.getVersionNumber())) {
            log.info("Peer negotiated V{} — app-layer protocols not activated",
                    acceptVersion.getVersionNumber());
            return;
        }
        appLayerNegotiated = true;
        if (appMsgEnabled)
            activateAppMsgSubmission();
        // AppChainSync (103) needs no activation: it is request-driven and
        // only sends when the caller invokes requestRange().
    }

    private void activateAppMsgSubmission() {
        setupListeners();
        appMsgAgent.sendNextMessage();  // sends MsgInit with our chain-ids
        log.info("AppMsgSubmission protocol activated (MsgInit sent, chains: {})",
                appMsgConfig.getChainIds());
    }
}
