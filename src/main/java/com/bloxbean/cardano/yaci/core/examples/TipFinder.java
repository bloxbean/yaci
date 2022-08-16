package com.bloxbean.cardano.yaci.core.examples;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.Disposable;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TipFinder extends ChainSyncAgentListener{
    private String host;
    private int port;
    private final Point wellKnownPoint;
    private Agent agent;
    private Disposable disposable;
    BlockingQueue blockingQueue = new LinkedBlockingQueue();

    public TipFinder(String host, int port, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;

        initAgent();
    }

    private void initAgent() {
        N2NClient n2CClient = null;
        n2CClient = new N2NClient(host, port);

        this.agent = new ChainsyncAgent(new Point[] {wellKnownPoint});
        agent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                blockingQueue.add(tip);
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                blockingQueue.add(tip);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                blockingQueue.add(tip);
            }
        });

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic()));
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("************** HANDSHAKE Successful ***************");
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("********* ERROR ** {}", reason);
            }
        });

        try {
            disposable = n2CClient.start(handshakeAgent,
                    agent);
        } catch (Exception e) {
            log.error("Error in main thread", e);
        }
    }

    public Tip findTip() {
        agent.sendNextMessage();

        try {
            return (Tip)blockingQueue.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
        if (disposable != null)
            disposable.dispose();
    }

    public static void main(String[] args) {
        Point point = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        TipFinder tipFinder = new TipFinder("192.168.0.228", 6000, point);

        Tip tip = tipFinder.findTip();
        log.info("Tip is >> " + tip);

        tipFinder.shutdown();
    }
}
