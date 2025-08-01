package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;

class PeerClientTest {

    @Test
    void startSync() throws InterruptedException {
        PeerClient peerClient = new PeerClient(Constants.PREPROD_PUBLIC_RELAY_ADDR,
                Constants.PREVIEW_PUBLIC_RELAY_PORT,
                Constants.PREPROD_PROTOCOL_MAGIC, Constants.WELL_KNOWN_PREPROD_POINT);

        peerClient.connect(new BlockChainDataListener() {
            @Override
            public void onRollback(Point point) {
                    System.out.println("PeerClientTest.onRollback: " + point);
            }

            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println("Peerclient.block : " + block.getHeader().getHeaderBody().getBlockNumber());
            }

            public void intersactFound(Tip tip, Point point) {
                System.out.println("PeerClientTest.intersactFound: " + tip + ", " + point);
            }

        }, null);

        peerClient.startSync(new Point(96734565, "26972da27366f15f86fa6844c257ccce117596e839cabad0372390047e71519c"));

        while (true) {
            Thread.sleep(10000);
        }
    }
}
