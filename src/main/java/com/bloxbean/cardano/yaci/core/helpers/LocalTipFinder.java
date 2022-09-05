package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.network.NodeClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

@Slf4j
public class LocalTipFinder extends ReactiveFetcher<Tip> {
    private String nodeSocketFile;
    private final Point wellKnownPoint;
    private HandshakeAgent handshakeAgent;
    private LocalChainSyncAgent chainSyncAgent;
    private NodeClient nodeClient;

    private VersionTable versionTable;
    private String tipRequest = "TIP_REQUEST";

    public LocalTipFinder(String nodeSocketFile, Point wellKnownPoint, long protocolMagic) {
        this.nodeSocketFile = nodeSocketFile;
        this.wellKnownPoint = wellKnownPoint;

        versionTable = N2CVersionTableConstant.v1AndAbove(protocolMagic);
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        this.chainSyncAgent = new LocalChainSyncAgent(new Point[]{wellKnownPoint});

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                if (log.isDebugEnabled())
                    log.debug("Handshake ok");
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.error("Handshake Error : {}", reason);
            }
        });

        this.nodeClient = new N2CClient(nodeSocketFile, handshakeAgent, chainSyncAgent);
    }

    @Override
    public void start(Consumer<Tip> consumer) {
        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                if (consumer != null)
                    consumer.accept(tip);
            }
        });
        nodeClient.start();
    }

    public Mono<Tip> find() {
        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                applyMonoSuccess(tipRequest, tip);
            }
        });

        return Mono.create(tipMonoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to find tip");
            storeMonoSinkReference(tipRequest, tipMonoSink);
            if (!nodeClient.isRunning())
                nodeClient.start();
            else {
                chainSyncAgent.reset(wellKnownPoint);
                chainSyncAgent.sendNextMessage();
            }
        });
    }

    public void shutdown() {
        if (nodeClient != null)
            nodeClient.shutdown();
    }

    @Override
    public boolean isRunning() {
        return nodeClient.isRunning();
    }

}
