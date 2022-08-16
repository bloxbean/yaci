package com.bloxbean.cardano.yaci.core.examples;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.network.Disposable;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BlockFetcher {
    private String host;
    private int port;
    private VersionTable versionTable;

    public BlockFetcher(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
    }

    public void start(Point from, Point to) {
        N2NClient n2CClient = new N2NClient(host, port);
        BlockfetchAgent blockfetchAgent = new BlockfetchAgent(from, to);

        Disposable disposable = null;
        try {
            disposable = n2CClient.start(new HandshakeAgent(versionTable), blockfetchAgent);
        } catch (Exception e) {
           log.error("Error in main thread", e);
        }

         blockfetchAgent.sendNextMessage();

        while (!blockfetchAgent.isDone()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {

            }
        }

        log.info("Dispose now !!!");
        disposable.dispose();
    }

    public static void main(String[] args) {
        Point from = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
//        Point from = new Point(43847831, "15b9eeee849dd6386d3770b0745e0450190f7560e5159b1b3ab13b14b2684a45");
//        Point to = new Point(43847844, "ff8d558a3d5a0e058beb3d94d26a567f75cd7d09ff5485aa0d0ebc38b61378d4");
        // Point to = new Point(43848472, "7e79488986d2961605259b5ed28b7c3179ca8fc85e34f9050adcb0d7e19f6871");
//        Point from = new Point(68571139, "7e0b5c20b2d6b76238bd11dc6c58c5ad91d74d4a4b2fe3e80bcf30deda1d9d35");
        Point to = new Point(68752024, "d0adb6c30c548bff7fd21f94f29447e21587c08977a950a3c472b8ce0e56d084");

//        Point from = new Point(68925707, "ddf38fece4cc7a4d132a186a347a15306bc409559d23826a912c654d0d0f3745");
//        Point to = new Point(68925707, "ddf38fece4cc7a4d132a186a347a15306bc409559d23826a912c654d0d0f3745");

        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        BlockFetcher blockFetcher = new BlockFetcher("192.168.0.228", 6000, versionTable);
        blockFetcher.start(from, to);
    }
}
