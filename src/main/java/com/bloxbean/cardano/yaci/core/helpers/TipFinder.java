package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public class TipFinder extends ChainSyncAgentListener implements Fetcher<Tip> {
    private String host;
    private int port;
    private final Point wellKnownPoint;
    private HandshakeAgent handshakeAgent;
    private ChainsyncAgent chainSyncAgent;
    private N2NClient n2NClient;

    public TipFinder(String host, int port, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic()));
        this.chainSyncAgent = new ChainsyncAgent(new Point[] {wellKnownPoint});

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

    @Override
    public void start(Consumer<Tip> consumer) {
        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                consumer.accept(tip);
            }
        });
        n2NClient.start();
    }

    public void shutdown() {
        if (n2NClient != null)
            n2NClient.shutdown();
    }

    @Override
    public boolean isRunning() {
        return n2NClient.isRunning();
    }

    public static void main(String[] args) {
        Point point = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        TipFinder tipFinder = new TipFinder("192.168.0.228", 6000, point);
        tipFinder.start(tip -> {
            System.out.println("Tip found >>>> " + tip);
        });

        tipFinder.shutdown();
    }
}
