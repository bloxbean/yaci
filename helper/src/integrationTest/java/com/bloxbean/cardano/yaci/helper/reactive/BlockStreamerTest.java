package com.bloxbean.cardano.yaci.helper.reactive;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.NetworkType;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class BlockStreamerTest {

    @Test
    void of() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.preprod().getProtocolMagic());
        Point wellKnownPoint = new Point(46770229, "580c39df75eaf0b69e30dec115ba57e7062a006c4071dc47542ea8f2c8405c53");

        Flux<Block> flux = BlockStreamer.fromLatest(Constants.PREPROD_IOHK_RELAY_ADDR, Constants.PREPROD_IOHK_RELAY_PORT, wellKnownPoint, versionTable).stream();

        CountDownLatch countDownLatch = new CountDownLatch(1);
        List<Block> blocks = new ArrayList<>();
        Disposable disposable = flux.subscribe(block -> {
            log.info(">>>>>>>>" + block.getHeader().getHeaderBody().getBlockNumber());
            blocks.add(block);
            countDownLatch.countDown();
        });

        countDownLatch.await(60, TimeUnit.SECONDS);
        disposable.dispose();

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isGreaterThan(2696981);
    }

    @Test
    void streamLatestFromPreprod() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        Flux<Amount> flux = BlockStreamer.fromPoint(NetworkType.PREPROD,
                        Constants.WELL_KNOWN_PREPROD_POINT)
                .stream()
                .map(block -> {
                    System.out.println("\n-----------------------------------------------------------");
                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
                    return block.getTransactionBodies();
                })
                .take(2)
                .flatMap(transactionBodies ->
                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
                .flatMap(transactionOutputs ->
                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
                .map(Function.identity());

        flux.subscribe(amount -> {
            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
            countDownLatch.countDown();
        });

        countDownLatch.await(60, TimeUnit.SECONDS);
    }

    @Test
    void streamLatestFromPreprod_fromByron() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        Flux<Amount> flux = BlockStreamer.fromPoint(NetworkType.PREPROD,
                        Constants.WELL_KNOWN_PREPROD_POINT)
                .stream()
                .map(block -> {
                    System.out.println("\n-----------------------------------------------------------");
                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
                    return block.getTransactionBodies();
                })
                .take(2)
                .flatMap(transactionBodies ->
                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
                .flatMap(transactionOutputs ->
                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
                .map(Function.identity());

        flux.subscribe(amount -> {
            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
            countDownLatch.countDown();
        });

        countDownLatch.await(40, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS)
    void streamFromPointFromPreprod() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        Flux<Amount> flux = BlockStreamer.fromPoint(NetworkType.PREPROD, new Point(46770233, "2c0dfd9fef43d5dcf80d4614a2bc891f9dd5c04b757ab0221165642b17f13885"))
                .stream()
                .map(block -> {
                    System.out.println("\n-----------------------------------------------------------");
                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
                    return block.getTransactionBodies();
                })
                .take(1)
                .flatMap(transactionBodies ->
                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
                .flatMap(transactionOutputs ->
                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
                .map(Function.identity());

        flux.subscribe(amount -> {
            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
            countDownLatch.countDown();
        });

        countDownLatch.await(40, TimeUnit.SECONDS);
    }

    @Test
    void streamForRangeFromPreprod() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(2);
        Point from = new Point(53779466, "6fbe92b70705384ad9d79c0fa4d7dcbda4d57b9452402429b7e102d51ba3fdbd");
        Point to = new Point(53779632, "b42ef491d7e64304310eab9feff12d3ecabe4208df03bb32f56625dff45ae28f");

        Flux<Amount> flux = BlockStreamer.forRange(NetworkType.PREPROD, from, to)
                .stream()
                .map(block -> {
                    System.out.println("\n-----------------------------------------------------------");
                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
                    return block.getTransactionBodies();
                })
                .flatMap(transactionBodies ->
                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
                .flatMap(transactionOutputs ->
                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
                .map(Function.identity());

        Disposable disposable = flux.subscribe(amount -> {
            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
        });

        flux.subscribe(amount -> {
            System.out.println("I also got the amount>> " + amount);
            countDownLatch.countDown();
        });

        countDownLatch.await(40, TimeUnit.SECONDS);
    }
}
