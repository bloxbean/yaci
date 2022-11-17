package com.bloxbean.cardano.yaci.core.reactive;

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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
class BlockStreamerTest {

    @Test
    void of() throws InterruptedException {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        Point wellKnownPoint = new Point(17625824, "765359c702103513dcb8ff4fe86c1a1522c07535733f31ff23231ccd9a3e0247");

        Flux<Block> flux = BlockStreamer.fromLatest(Constants.MAINNET_IOHK_RELAY_ADDR, Constants.MAINNET_IOHK_RELAY_PORT, wellKnownPoint, versionTable).stream();

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
        assertThat(blocks.get(0).getHeader().getHeaderBody().getBlockNumber()).isGreaterThan(8025579);
    }

    @Test
    void streamLatestFromMainnet() throws InterruptedException {
        Flux<Amount> flux = BlockStreamer.fromPoint(NetworkType.MAINNET,
                        Constants.WELL_KNOWN_MAINNET_POINT)
                .stream()
                .map(block -> {
                    System.out.println("\n-----------------------------------------------------------");
                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
                    return block.getTransactionBodies();
                })
                //.take(2)
                .flatMap(transactionBodies ->
                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
                .flatMap(transactionOutputs ->
                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
                .map(Function.identity());

        flux.subscribe(amount -> {
            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
        });

        while (true) {
            Thread.sleep(1000);
        }
    }

    @Test
        // @Timeout(value = 80000, unit = TimeUnit.MILLISECONDS)
    void streamLatestFromPrePod_fromByron() throws InterruptedException {
        Flux<Amount> flux = BlockStreamer.fromPoint(NetworkType.PREPOD,
                        Constants.WELL_KNOWN_PREPOD_POINT)
                .stream()
                .map(block -> {
                    System.out.println("\n-----------------------------------------------------------");
                    System.out.println(String.format("Block : %d", block.getHeader().getHeaderBody().getBlockNumber()));
                    return block.getTransactionBodies();
                })
                //.take(2)
                .flatMap(transactionBodies ->
                        Flux.fromStream(transactionBodies.stream().map(transactionBody -> transactionBody.getOutputs())))
                .flatMap(transactionOutputs ->
                        Flux.fromStream(transactionOutputs.stream().map(transactionOutput -> transactionOutput.getAmounts())))
                .flatMap(amounts -> Flux.fromStream(amounts.stream()))
                .map(Function.identity());

        flux.subscribe(amount -> {
            System.out.println(String.format("%30s : %d", amount.getAssetName(), amount.getQuantity()));
        });

        while (true) {
            Thread.sleep(1000);
        }
    }

    @Test
    @Timeout(value = 40000, unit = TimeUnit.MILLISECONDS)
    void streamFromPointFromMainnet() throws InterruptedException {
        Flux<Amount> flux = BlockStreamer.fromPoint(NetworkType.MAINNET, new Point(39916796, "e72579ff89dc9ed325b723a33624b596c08141c7bd573ecfff56a1f7229e4d09"))
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
        });

        while (true) {
            Thread.sleep(1000);
        }
    }

    @Test
    void streamForRangeFromMainnet() throws InterruptedException {
        Point from = new Point(43847831, "15b9eeee849dd6386d3770b0745e0450190f7560e5159b1b3ab13b14b2684a45");
        Point to = new Point(43847844, "ff8d558a3d5a0e058beb3d94d26a567f75cd7d09ff5485aa0d0ebc38b61378d4");

        Flux<Amount> flux = BlockStreamer.forRange(NetworkType.MAINNET, from, to)
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
        });

        while (true) {
            Thread.sleep(1000);
        }
    }
}
