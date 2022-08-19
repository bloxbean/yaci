package com.bloxbean.cardano.yaci.core.reactive;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class BlockStreamerTest {

//    @Test
//    void of() throws InterruptedException {
//        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
//        Point wellKnownPoint = new Point(17625824, "765359c702103513dcb8ff4fe86c1a1522c07535733f31ff23231ccd9a3e0247");
////        ChainSyncFetcherFromLatest chainSyncFetcher = new ChainSyncFetcherFromLatest("192.168.0.228", 6000, versionTable, wellKnownPoint);
//
//        Flux<Block> flux = BlockStreamer.fromLatest("192.168.0.228", 6000, versionTable, wellKnownPoint);
//
//        AtomicInteger counter = new AtomicInteger(0);
//        Disposable disposable = flux.map(block -> block.getTransactionBodies())
//                .subscribe(vrfCert -> log.info("&&&&&&&&&&&&&&&&&&&&&& " + vrfCert));
//
//        while(counter.get() < 100) {
//            Thread.sleep(1000);
//        }
//    }
//
//    @Test
//    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS)
//    void streamLatestFromMainnet() throws InterruptedException {
//        Flux<Amount> flux = BlockStreamer.fromLatest(true)
//                .map(block -> {
//                    System.out.println("\n-----------------------------------------------------------");
//                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
//                    return block.getTransactionBodies();
//                })
//                .take(2)
//                .flatMap(transactionBodies ->
//                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
//                .flatMap(transactionOutputs ->
//                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
//                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
//                .map(Function.identity());
//
//        flux.subscribe(amount -> {
//            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
//        });
//
//        while(true) {
//            Thread.sleep(1000);
//        }
//    }
//
//    @Test
//    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
//    void streamLatestFromTestnet() throws InterruptedException {
//        Flux<Amount> flux = BlockStreamer.fromLatest(true)
//                .map(block -> {
//                    System.out.println("\n-----------------------------------------------------------");
//                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
//                    return block.getTransactionBodies();
//                })
//                .take(1)
//                .flatMap(transactionBodies ->
//                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
//                .flatMap(transactionOutputs ->
//                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
//                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
//                .map(Function.identity());
//
//        flux.subscribe(amount -> {
//            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
//        });
//
//        while(true) {
//            Thread.sleep(1000);
//        }
//    }
}
