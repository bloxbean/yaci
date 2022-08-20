package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
public class BlockFetcher implements Fetcher<Block> {
    private String host;
    private int port;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private BlockfetchAgent blockfetchAgent;
    private N2NClient n2CClient;

    public BlockFetcher(String host, int port, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        blockfetchAgent = new BlockfetchAgent();
        n2CClient = new N2NClient(host, port, handshakeAgent, blockfetchAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                blockfetchAgent.sendNextMessage();
            }
        });

        blockfetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void onStateUpdate(State oldState, State newState) {
                blockfetchAgent.sendNextMessage();
            }
        });
    }

    public void start(Consumer<Block> receiver) {
        blockfetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                receiver.accept(block);
            }
        });

        n2CClient.start();
    }

    public void fetch(Point from, Point to) {
        if (!n2CClient.isRunning())
            throw new IllegalStateException("fetch() should be called after start()");

        blockfetchAgent.resetPoints(from, to);
        if (!blockfetchAgent.isDone())
            blockfetchAgent.sendNextMessage();
        else
            log.warn("Agent status is Done. Can't reschedule new points.");
    }

    public void addBlockFetchListener(BlockfetchAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            blockfetchAgent.addListener(listener);
    }

    @Override
    public boolean isRunning() {
        return n2CClient.isRunning();
    }

    @Override
    public void shutdown() {
        n2CClient.shutdown();
    }

    public static void main(String[] args) {
//        Point from = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
        Point from = new Point(43847831, "15b9eeee849dd6386d3770b0745e0450190f7560e5159b1b3ab13b14b2684a45");
        Point to = new Point(43847844, "ff8d558a3d5a0e058beb3d94d26a567f75cd7d09ff5485aa0d0ebc38b61378d4");
//         Point to = new Point(43848472, "7e79488986d2961605259b5ed28b7c3179ca8fc85e34f9050adcb0d7e19f6871");
//        Point from = new Point(68571139, "7e0b5c20b2d6b76238bd11dc6c58c5ad91d74d4a4b2fe3e80bcf30deda1d9d35");
//        Point to = new Point(68752024, "d0adb6c30c548bff7fd21f94f29447e21587c08977a950a3c472b8ce0e56d084");
//        Point from = new Point(68925707, "ddf38fece4cc7a4d132a186a347a15306bc409559d23826a912c654d0d0f3745");
//        Point to = new Point(68925707, "ddf38fece4cc7a4d132a186a347a15306bc409559d23826a912c654d0d0f3745");

        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        BlockFetcher blockFetcher = new BlockFetcher("192.168.0.228", 6000, versionTable);

        blockFetcher.addBlockFetchListener(new BlockfetchAgentListener() {
            int counter = 0;
            @Override
            public void batchDone() {
                log.info("Batch Done ------>");
            }

            @Override
            public void readyForNextBatch() {
                if (counter == 1) {
                    blockFetcher.shutdown();
                    return;
                }
                counter++;
                Point from = new Point(68752024, "d0adb6c30c548bff7fd21f94f29447e21587c08977a950a3c472b8ce0e56d084");
                Point to = new Point(68752024, "d0adb6c30c548bff7fd21f94f29447e21587c08977a950a3c472b8ce0e56d084");

                blockFetcher.fetch(from, to);
            }
        });

        blockFetcher.start(block -> {
            log.info("Block ******* {} -- {}", block.getHeader().getHeaderBody().getBlockNumber(), block.getHeader().getHeaderBody().getSlot());
        });

        blockFetcher.fetch(from, to);
    }
}
