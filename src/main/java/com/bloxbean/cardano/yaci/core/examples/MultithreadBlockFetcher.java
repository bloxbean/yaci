package com.bloxbean.cardano.yaci.core.examples;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.test.GetPoints;

import java.util.ArrayList;
import java.util.List;

public class MultithreadBlockFetcher {
    private String host;
    private int port;
    private VersionTable versionTable;

    public MultithreadBlockFetcher(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
    }

    public void start() throws Exception {
        N2NClient n2CClient = new N2NClient(host, port);

        int noOfThreads = 6;
        GetPoints getPoints = new GetPoints();
        List<Point> points = getPoints.fetchPoints(noOfThreads);

        List<Thread> threads = new ArrayList<>();
        for (int i=0; i < noOfThreads-1; i++) {
            Point point = points.get(i);
            final int index = i + 1; //Also used for next index
            Runnable fetcher = () -> {
                Agent[] agents = new Agent[]{new BlockfetchAgent(point, points.get(index))};
                try {
                    n2CClient.start(new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic())), agents);
                    while (true) {
                        agents[0].sendNextMessage();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.printf("Error in main thread", e);
                }
            };

            Thread t1 = new Thread(fetcher);
            t1.start();
            threads.add(t1);
        }

//        //Main fetcher
//        Runnable fetcher = () -> {
//            Agent[] agents = new Agent[]{new ChainsyncAgent(new Point[]{points.get(1)}, 0, noOfThreads)};
//            try {
//                n2CClient.start(new HandshakeAgent(N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic())), agents);
//            } catch (Exception e) {
//                e.printStackTrace();
//                System.out.printf("Error in main thread", e);
//            }
//        };

//        Thread t1 = new Thread(fetcher);
//        t1.start();
//        threads.add(t1);

        for (Thread t: threads)
            t.join();
    }

    public static void main(String[] args) throws Exception {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        MultithreadBlockFetcher chainSyncFetcher = new MultithreadBlockFetcher("192.168.0.228", 6000, versionTable);
        chainSyncFetcher.start();
    }
}
