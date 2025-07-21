package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.*;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.node.chain.InMemoryChainState;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class Node implements BlockChainDataListener, TxSubmissionListener {
    private String remoteCardanoHost;
    private int remoteCardanoPort;
    private long protocolMagic;

    private ChainState chainState;

    private PeerClient peerClient;

    private TipFinder tipFinder;
    private GenesisBlockFinder genesisBlockFinder;

    private TxBuild txBuild = new TxBuild();

    AtomicBoolean readyForTxSubmission = new AtomicBoolean(false);

    Map<String, Boolean> txStatusMap = new ConcurrentHashMap<>();

    public Node(String remoteCardanoHost, int remoteCardanoPort, long protocolMagic) {
        this.remoteCardanoHost = remoteCardanoHost;
        this.remoteCardanoPort = remoteCardanoPort;
        this.protocolMagic = protocolMagic;
        this.chainState = new InMemoryChainState();

        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);
    }

//    public void startTxSubmissionClient() {
//        txSubmissionClient = new TxSubmissionClient(remoteCardanoHost, remoteCardanoPort, N2NVersionTableConstant.v11AndAbove(protocolMagic));
//        txSubmissionClient.addListener(this);
//        txSubmissionClient.start();
//
//        while(true) {
//            submitTx();
//            try {
//                Thread.sleep(3000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
//    }

    private void submitTx() {
        //        System.out.println("Building tx...");
        var transactions = txBuild.buildTransaction();
        for (var transaction : transactions) {
            var txHash = TransactionUtil.getTxHash(transaction);
            if (txStatusMap.get(txHash) != null) {
                System.out.println("Transaction already submitted : " + txHash);
                continue;
            }

            byte[] txBytes = null;
            try {
                txBytes = transaction.serialize();
            } catch (CborSerializationException e) {
                e.printStackTrace();
//            throw new RuntimeException(e);
            }
            System.out.println("Submitting Tx hash : " + txHash);

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        int min = 1;
                        int max = 65000;
                        int randomNum = (int) (Math.random() * (max - min + 1)) + min;
                        peerClient.sendKeepAliveMessage(randomNum);
                    }
                }
            });
            t.start();

            peerClient.submitTxBytes(txHash, txBytes, TxBodyType.CONWAY);
            txStatusMap.put(txHash, false);
            System.out.println("Transaction : " + txHash);
        }
    }

    public void start() {
        TipFinder tipFinder = new TipFinder(remoteCardanoHost, remoteCardanoPort,
                Point.ORIGIN, protocolMagic);

        var tip = tipFinder.find().block(Duration.ofSeconds(10));
        log.info("Remote tip : {}", tip);
        tipFinder.shutdown();

        //Get genesis block
        genesisBlockFinder = new GenesisBlockFinder(remoteCardanoHost, remoteCardanoPort, protocolMagic);
        var startPoint = genesisBlockFinder.getGenesisAndFirstBlock();

        //Get local tip
        ChainTip localTip = chainState.getTip();
        log.info("Local tip : {}", localTip);

        peerClient = new PeerClient(remoteCardanoHost, remoteCardanoPort, protocolMagic, startPoint.get().getFirstBlock());
        peerClient.startSyncFromTip(this, this);
        peerClient.enableTxSubmission();

        System.out.println("Start submitting txs >>>>>>>>>>");
        while(true) {
            if (!readyForTxSubmission.get())
                continue;

            submitTx();
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

//        if (localTip == null || (localTip != null && tip != null && (tip.getPoint().getSlot() - localTip.getSlot() > 1800))) {
//            peerClient = new PeerClient(remoteCardanoHost, remoteCardanoPort, protocolMagic, startPoint.get().getFirstBlock());
//
//            peerClient.startSyncFromTip(this);
//            Point from = startPoint.map(sp -> sp.getFirstBlock()).orElse(Point.ORIGIN);
//            Point to = new Point(tip.getPoint().getSlot(), tip.getPoint().getHash());
//            if (localTip != null)
//                from = new Point(localTip.getSlot(), HexUtil.encodeHexString(localTip.getBlockHash()));
//
//            blockRangeSync.fetch(from, to);
//        } else {
//            Point localTipPoint = new Point(localTip.getSlot(), HexUtil.encodeHexString(localTip.getBlockHash()));
//            blockSync = new BlockSync(remoteCardanoHost, remoteCardanoPort, protocolMagic, localTipPoint);
//            blockSync.startSync(localTipPoint, this);
//        }
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        chainState.storeBlock(HexUtil.decodeHexString(byronBlock.getHeader().getBlockHash()),
                byronBlock.getHeader().getConsensusData().getAbsoluteSlot(),
                byronBlock.getHeader().getConsensusData().getDifficulty().longValue(),
                HexUtil.decodeHexString(byronBlock.getCbor()));
        log.info("Storing block : {}", byronBlock.getHeader().getConsensusData().getDifficulty());
        System.out.println("Storing block : " + byronBlock.getHeader().getConsensusData().getDifficulty());
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        chainState.storeBlock(HexUtil.decodeHexString(byronEbBlock.getHeader().getBlockHash()),
                byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot(),
                byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue(),
                HexUtil.decodeHexString(byronEbBlock.getCbor()));
        log.info("Storing block : {}", byronEbBlock.getHeader().getConsensusData().getDifficulty());
    }

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        chainState.storeBlock(HexUtil.decodeHexString(block.getHeader().getHeaderBody().getBlockHash()),
                block.getHeader().getHeaderBody().getSlot(),
                block.getHeader().getHeaderBody().getBlockNumber(),
                HexUtil.decodeHexString(block.getCbor()));
        log.info("Storing block : {}", block.getHeader().getHeaderBody().getBlockNumber());
        System.out.println("Storing block : " + block.getHeader().getHeaderBody().getBlockNumber());

        transactions.stream()
                .forEach(transaction -> {
                    if (txStatusMap.get(transaction.getTxHash()) != null) {
                        txStatusMap.put(transaction.getTxHash(), true);
                        System.out.println("Transaction status confirmed : " + transaction.getTxHash());
                        txStatusMap.remove(transaction.getTxHash());
                    }
                });
    }


    @Override
    public void onRollback(Point point) {
        chainState.rollbackTo(point.getSlot());
        log.info("Rollback to slot : {}", point.getSlot());
    }

    @Override
    public void onDisconnect() {
        BlockChainDataListener.super.onDisconnect();
    }

    public static void main(String[] args) throws InterruptedException {
        long protocolMagic = Constants.PREPROD_PROTOCOL_MAGIC;
        Thread virtualThread1 = Thread.ofVirtual().start(() -> {
            String remoteCardanoHost = Constants.PREPROD_PUBLIC_RELAY_ADDR;
            int remoteCardanoPort = Constants.PREPROD_PUBLIC_RELAY_PORT;
            Node nodeMain = new Node(remoteCardanoHost, remoteCardanoPort, protocolMagic);
            nodeMain.start();
        });

//        nodeMain.startTxSubmissionClient();

        Thread virtualThread2 = Thread.ofVirtual().start(() -> {
                    String remoteCardanoHost2 = "51.77.24.220";
                    int remoteCardanoPort2 = 4002;
                    Node nodeMain2 = new Node(remoteCardanoHost2, remoteCardanoPort2, protocolMagic);
                    nodeMain2.start();
                });

        Thread virtualThread3 = Thread.ofVirtual().start(() -> {
            String remoteCardanoHost3 = "154.12.230.21";
            int remoteCardanoPort3 = 4501;
            Node nodeMain3 = new Node(remoteCardanoHost3, remoteCardanoPort3, protocolMagic);
            nodeMain3.start();
        });

        virtualThread1.join();
    }

    @Override
    public void handleRequestTxs(RequestTxs requestTxs) {
        System.out.println("RequestTxs received >> " + requestTxs.getTxIds());
    }

    @Override
    public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
        System.out.println("RequestTxIdsNonBlocking received >> " + requestTxIds.getReqTxIds());
    }

    @Override
    public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
        System.out.println("RequestTxIdsBlocking received >> " + requestTxIds.getReqTxIds());
        if (!readyForTxSubmission.get())
            readyForTxSubmission.set(true);
    }

    @Override
    public void onStateUpdate(State oldState, State newState) {
        TxSubmissionListener.super.onStateUpdate(oldState, newState);
    }
}
