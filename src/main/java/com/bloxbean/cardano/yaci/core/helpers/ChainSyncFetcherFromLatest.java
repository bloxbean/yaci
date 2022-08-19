package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChainSyncFetcherFromLatest {
    private String host;
    private int port;
    private VersionTable versionTable;
    private Point wellKnownPoint;
    private Point currentPoint;
    private boolean tipFound = false;

    public ChainSyncFetcherFromLatest(String host, int port, VersionTable versionTable) {
        this(host, port, versionTable, null);
    }

    public ChainSyncFetcherFromLatest(String host, int port, VersionTable versionTable, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        this.wellKnownPoint = wellKnownPoint;
    }

    public void start() throws Exception {

        HandshakeAgent handshakeAgent = new HandshakeAgent(versionTable);
        ChainsyncAgent chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        BlockfetchAgent blockFetch = new BlockfetchAgent(wellKnownPoint, wellKnownPoint);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                //start
                chainSyncAgent.sendNextMessage();
            }
        });

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
              //  Point point = tip.getPoint();

                log.info("Intersect found : Point : {},  Tip: {}", point, tip);
                log.info("Tip Found: {}", tipFound);

                if (!tip.getPoint().equals(point) && !tipFound) {
                    chainSyncAgent.reset(tip.getPoint());
                    tipFound = true;
                }

//                if ((tip.getPoint().getSlot() - point.getSlot()) > 60) { //60 slot difference, then reset
//                    ((ChainsyncAgent) chainSyncAgent).reset(tip.getPoint());
//                }

                //chainSyncAgent.sendNextMessage();
            }

            @Override
            public void intersactNotFound(Point point) {
                log.error("IntersactNotFound: {}", point);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                long slot = blockHeader.getHeaderBody().getSlot();
                String hash = blockHeader.getHeaderBody().getBlockHash();

                log.info("Rolled to slot: {}, block: {}", blockHeader.getHeaderBody().getSlot(), blockHeader.getHeaderBody().getBlockNumber());

                blockFetch.reset(new Point(slot, hash), new Point(slot, hash));
                log.info("Trying to fetch block for {}", new Point(slot, hash));
                blockFetch.sendNextMessage();
            }

            @Override
            public void onStateUpdate(State oldState, State newState) {
                chainSyncAgent.sendNextMessage();
            }
        });

        blockFetch.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                log.info("Block Found >> " + block);
            }
        });

        N2NClient n2CClient = new N2NClient(host, port, handshakeAgent,
                chainSyncAgent, blockFetch);

        n2CClient.start();

        while (true) {
            Thread.sleep(100);
        }
    }

    public static void main(String[] args) throws Exception {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        Point wellKnownPoint = new Point(17625824, "765359c702103513dcb8ff4fe86c1a1522c07535733f31ff23231ccd9a3e0247");
        ChainSyncFetcherFromLatest chainSyncFetcher = new ChainSyncFetcherFromLatest("192.168.0.228", 6000, versionTable, wellKnownPoint);
        chainSyncFetcher.start();
    }
}
