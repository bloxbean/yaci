package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.helpers.api.ReactiveFetcher;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/**
 * Use this helper to find the tip of a remote Cardano node using Node to Node mini-protocol
 * The result can be obtained through a Consumer function or through a {@link Mono}
 *
 * <p>
 * 1. Pass a consumer function to {@link #start(Consumer)} to receive the result (tip) <br>
 * Example : Get Tip through Consumer function
 * </p>
 * <pre>
 * {@code
 * TipFinder tipFinder = new TipFinder(node, nodePort, Constants.WELL_KNOWN_MAINNET_POINT, Constants.MAINNET_PROTOCOL_MAGIC);
 *
 * tipFinder.start(tip -> {
 *      System.out.println("Tip found >> " + tip);
 * });
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
 */
@Slf4j
public class TipFinder extends ReactiveFetcher<Tip> {
    private String host;
    private int port;
    private final Point wellKnownPoint;

    private HandshakeAgent handshakeAgent;
    private ChainsyncAgent chainSyncAgent;
    private N2NClient n2NClient;
    private VersionTable versionTable;
    private String tipRequest = "TIP_REQUEST";

    /**
     * Construct TipFinder to find tip of a remote Cardano node
     *
     * @param host           Cardano node host
     * @param port           Cardano node port
     * @param wellKnownPoint a well known point
     * @param protocolMagic  network protocol magic
     */
    public TipFinder(String host, int port, Point wellKnownPoint, long protocolMagic) {
        this(host, port, wellKnownPoint, N2NVersionTableConstant.v4AndAbove(protocolMagic));
    }

    /**
     * Construct TipFinder to find tip of a remote Cardano node
     *
     * @param host           Cardano node host
     * @param port           Cardano node port
     * @param wellKnownPoint a well known point
     * @param versionTable   network protocol magic
     */
    public TipFinder(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
        this.versionTable = versionTable;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        this.chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});

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

        this.n2NClient = new N2NClient(host, port, handshakeAgent, chainSyncAgent);
    }

    /**
     * Call this method or {@link #start()} before querying for the tip
     *
     * @param consumer A {@link Consumer} function to receive the result
     */
    @Override
    public void start(Consumer<Tip> consumer) {
        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                if (consumer != null)
                    consumer.accept(tip);
            }
        });

        if (!n2NClient.isRunning())
            n2NClient.start();
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
     * Mono<Tip> tipMono = tipFinder.find();
     *
     * Tip tip = tipMono.block();
     * }
     * </pre>
     *
     * @return Mono with {@link Tip}
     */
    public Mono<Tip> find() {
        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                applyMonoSuccess(tipRequest, tip);
            }
        });

        return Mono.create(tipMonoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to find tip");
            storeMonoSinkReference(tipRequest, tipMonoSink);
            if (!n2NClient.isRunning())
                n2NClient.start();
            else {
                next();
            }
        });
    }

    /**
     * Shutdown the connection
     */
    @Override
    public void shutdown() {
        if (n2NClient != null)
            n2NClient.shutdown();
    }

    /**
     * Check if the connection is alive
     *
     * @return true if yes, otherwise false
     */
    @Override
    public boolean isRunning() {
        return n2NClient.isRunning();
    }

    public static void main(String[] args) {
        Point point = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        TipFinder tipFinder = new TipFinder("192.168.0.228", 6000, point, Constants.MAINNET_PROTOCOL_MAGIC);
        tipFinder.start(tip -> {
            System.out.println("Tip found >>>> " + tip);
        });

        tipFinder.shutdown();
    }
}
