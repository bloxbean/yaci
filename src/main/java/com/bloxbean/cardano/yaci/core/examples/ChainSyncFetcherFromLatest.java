package com.bloxbean.cardano.yaci.core.examples;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.Disposable;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChainSyncFetcherFromLatest {
    private String host;
    private int port;
    private Point wellKnownPoint;
    private boolean tipFound = false;

    public ChainSyncFetcherFromLatest(String host, int port) {
        this(host, port, null);
    }

    public ChainSyncFetcherFromLatest(String host, int port, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
    }

    public void start() throws Exception {
        N2NClient n2CClient = new N2NClient(host, port);

        Agent chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        Agent blockFetch = new BlockfetchAgent(wellKnownPoint, wellKnownPoint);

        Disposable disposable =
                n2CClient.start(new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic())),
                        chainSyncAgent, blockFetch);

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
              //  Point point = tip.getPoint();
                System.out.println("Intersect found");

                if (!tip.getPoint().equals(point) && !tipFound) {
                    ((ChainsyncAgent) chainSyncAgent).reset(tip.getPoint());
                    tipFound = true;
                }
            }

            @Override
            public void intersactNotFound(Point point) {
                log.error("IntersactNotFound: {}", point);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                long slot = blockHeader.getHeaderBody().getSlot();
                String hash = blockHeader.getHeaderBody().getBlockHash();

                ((BlockfetchAgent)blockFetch).reset(new Point(slot, hash), new Point(slot, hash));
                log.info("Trying to fetch block for {}", new Point(slot, hash));
                blockFetch.sendNextMessage();
            }
        });

        blockFetch.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                log.info("Block Found >> " + block);
            }
        });

        while (true) {
            chainSyncAgent.sendNextMessage();
            Thread.sleep(100);
        }

    }

    public static void main(String[] args) throws Exception {

        Point wellKnownPoint = new Point(17625824, "765359c702103513dcb8ff4fe86c1a1522c07535733f31ff23231ccd9a3e0247");
        ChainSyncFetcherFromLatest chainSyncFetcher = new ChainSyncFetcherFromLatest("192.168.0.228", 6000, wellKnownPoint);
        chainSyncFetcher.start();
    }
}
