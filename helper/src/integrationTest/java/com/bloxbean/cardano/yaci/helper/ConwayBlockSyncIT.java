package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ConwayBlockSyncIT extends BaseTest{

    @Test
    void syncTest() throws Exception {
        BlockSync blockSync = new BlockSync(Constants.SANCHONET_PUBLIC_RELAY_ADDR, Constants.SANCHONET_PUBLIC_RELAY_PORT, Constants.SANCHONET_PROTOCOL_MAGIC, Constants.WELL_KNOWN_SANCHONET_POINT);

        blockSync.startSync(Point.ORIGIN, new BlockChainDataListener() {
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                System.out.println(block.getHeader().getHeaderBody().getBlockNumber());
                System.out.println("# of transactions >> " + transactions.size());
            }

            @Override
            public void onByronBlock(ByronMainBlock byronBlock) {
                System.out.println("Byron block: " + byronBlock);
            }

            @Override
            public void onByronEbBlock(ByronEbBlock byronEbBlock) {
                System.out.println("Byron EB block: " + byronEbBlock);
            }
        });

        while (true)
            Thread.sleep(5000);
    }
}
