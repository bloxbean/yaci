package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.helpers.api.ReactiveFetcher;
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

/**
 * Use this helper to find the tip of the local Cardano node using Node to Client mini-protocol
 * The result can be obtained through a Consumer function or through a {@link Mono}
 *
 * <p>
 * 1. Pass a consumer function to {@link #start(Consumer)} to receive the result (tip) <br>
 * Example : Get Tip through Consumer function
 * </p>
 * <pre>
 * {@code
 * LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, Constants.WELL_KNOWN_PREVIEW_POINT, Constants.PREVIEW_PROTOCOL_MAGIC);
 *
 * localTipFinder.start(tip -> {
 *   System.out.println("Tip found >> " + tip);
 * });
 *
 * localTipFinder.shutdown();
 * }
 * </pre>
 *
 * <p>
 * 2. Receive result (tip) through reactive {@link Mono} <br>
 * Example:
 * </p>
 * <pre>
 * {@code
 * LocalTipFinder localTipFinder = new LocalTipFinder(nodeSocketFile, Constants.WELL_KNOWN_PREVIEW_POINT, Constants.PREVIEW_PROTOCOL_MAGIC);
 *
 * Mono<Tip> tipMono = localTipFinder.find();
 *
 * Tip tip = tipMono.block();
 * }
 * </pre>
 *
 */
@Slf4j
public class LocalTipFinder extends ReactiveFetcher<Tip> {
    private String nodeSocketFile;
    private final Point wellKnownPoint;
    private HandshakeAgent handshakeAgent;
    private LocalChainSyncAgent chainSyncAgent;
    private NodeClient nodeClient;

    private VersionTable versionTable;
    private String tipRequest = "TIP_REQUEST";

    /**
     * Construct LocalTipFinder
     * @param nodeSocketFile
     * @param wellKnownPoint
     * @param protocolMagic
     */
    public LocalTipFinder(String nodeSocketFile, Point wellKnownPoint, long protocolMagic) {
        this(nodeSocketFile, wellKnownPoint, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Construct LocalTipFinder
     * @param nodeSocketFile
     * @param wellKnownPoint
     * @param versionTable
     */
    public LocalTipFinder(String nodeSocketFile, Point wellKnownPoint, VersionTable versionTable) {
        this.nodeSocketFile = nodeSocketFile;
        this.wellKnownPoint = wellKnownPoint;

        this.versionTable = versionTable;
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

    /**
     * Call this method or {@link #start()} before querying for the tip
     * @param consumer A {@link Consumer} function to receive the result
     */
    @Override
    public void start(Consumer<Tip> consumer) {
        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                if (consumer != null)
                    consumer.accept(tip);
            }
        });

        if (nodeClient != null)
            nodeClient.start();
    }

    /**
     * Call this method to re-fetch the tip when result is received through a {@link Consumer} function.
     * If tip is found using {@link #find()} method, this method should not be called.
     */
    public void next() {
        chainSyncAgent.reset(wellKnownPoint);
        chainSyncAgent.sendNextMessage();
    }

    /**
     * Find the tip through a reactive {@link Mono}
     * This method can be called multiple times
     *
     * <pre>
     * {@code
     * Mono<Tip> tipMono = localTipFinder.find();
     *
     * Tip tip = tipMono.block();
     * }
     * </pre>
     * @return Mono with {@link Tip}
     */
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
                next();
            }
        });
    }

    /**
     * Shutdown the connection
     */
    public void shutdown() {
        if (nodeClient != null)
            nodeClient.shutdown();
    }

    /**
     * Check if the connection is alive
     * @return true if yes, otherwise false
     */
    @Override
    public boolean isRunning() {
        return nodeClient.isRunning();
    }

}
