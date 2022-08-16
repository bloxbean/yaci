package com.bloxbean.cardano.yaci.core.test;

import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class GetPoints {

    private final BackendService backendService;
    public GetPoints() {
        backendService =
                new BFBackendService(Constants.BLOCKFROST_MAINNET_URL, "mainnetoNDIRLK34oGAGnjzGwWy9Uj1CgFdSVbS");
    }

    public List<Point> fetchPoints(int n) throws Exception {
        Block latestBlock = backendService.getBlockService().getLatestBlock().getValue();
        long latestBlockNo = latestBlock.getHeight();
        long latestSlot = latestBlock.getSlot();

        long startBlock = 5086523;
        long startSlot = 16588737;
        String startHash = "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a";

//        long startBlock = 7601140;
//        long startSlot = 68412094;
//        String startHash = "2a5e59c9658db81f1f7d62599251c8e8661da2e5f392b9e969f32f313ce4a269";

        long diff = latestBlockNo - startBlock;
        long batchSize = diff / n;

        long blockNo = startBlock;
        long slot = startSlot;
        String hash = startHash;

        List<Point> points = new ArrayList<>();
        Point startPoint = new Point(startSlot, startHash);
        points.add(startPoint);
        for (int i=0; i < n-1; i++) {
            log.info("Fetching next batch > " );
            blockNo += batchSize;
            Block block = backendService.getBlockService().getBlockByNumber(BigInteger.valueOf(blockNo)).getValue();
            slot = block.getSlot();
            hash = block.getHash();

            log.info(String.valueOf(blockNo));
            Point point = new Point(slot, hash);
            points.add(point);
        }

        return points;
    }

    public static void main(String[] args) throws Exception {
        GetPoints getPoints = new GetPoints();
        List<Point> points = getPoints.fetchPoints(20);

        log.info(String.valueOf(points));
    }
}

