package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Disabled
class LocalStateQueryClientTest extends BaseTest {

    @Test
    void startTimeQuery() throws InterruptedException {
        LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
        queryClient.start(result -> {
        });
        Mono<QueryResult> queryResultMono = queryClient.executeQuery(new SystemStartQuery());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        queryResultMono.subscribe(result -> {
            System.out.println(result);
            countDownLatch.countDown();
        });

        countDownLatch.await(20, TimeUnit.SECONDS);
    }

    @Test
    void chainPoint() throws InterruptedException {
        LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
        queryClient.start(result -> {
        });

        Mono<ChainPointQueryResult> chainPointQueryMono = queryClient.executeQuery(new ChainPointQuery());
        ChainPointQueryResult chainPointQueryResult = chainPointQueryMono.block(Duration.ofSeconds(8));
        System.out.println("ChainPoint >> " + chainPointQueryResult);

        Mono<Point> reAcquireMono = queryClient.reAcquire();
        reAcquireMono.block();

        Mono<BlockHeightQueryResult> blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
        BlockHeightQueryResult blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(20));

        System.out.println("BlockHeight >> " + blockHeightQueryResult);

        //TODO -- test
//        queryClient.release().block();
//        queryClient.acquire(chainPointQueryResult.getChainPoint()).block();

        blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
        blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(20));

        System.out.println("BlockHeight $$$$$$$$$ " + blockHeightQueryResult);


        queryClient.shutdown();
    }

    @Test
    void protocolParameters() throws InterruptedException {
        LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
        queryClient.start(result -> {
        });

        Thread.sleep(4000);
        Mono<CurrentProtocolParamQueryResult> mono = queryClient.executeQuery(new CurrentProtocolParamsQuery(Era.Alonzo));
        CurrentProtocolParamQueryResult result = mono.block(Duration.ofSeconds(8));
        System.out.println("Result >> " + result);

        queryClient.reAcquire();
        Thread.sleep(3000);

        Mono<BlockHeightQueryResult> blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
        BlockHeightQueryResult blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(20));

        System.out.println("BlockHeight >> " + blockHeightQueryResult);

        queryClient.shutdown();
    }

    @Test
    void epochNoQuery() throws InterruptedException {
        LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
        queryClient.start(result -> {
        });
        Mono<EpochNoQueryResult> queryResultMono = queryClient.executeQuery(new EpochNoQuery(Era.Alonzo));

        CountDownLatch countDownLatch = new CountDownLatch(1);
        queryResultMono.subscribe(result -> {
            System.out.println(result);
            countDownLatch.countDown();
        });

        countDownLatch.await(20, TimeUnit.SECONDS);
    }

    @Test
    void utxoByAddress() throws Exception {
        LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
        queryClient.start(result -> {
        });

//        Mono<UtxoByAddressQueryResult> queryResultMono = queryClient.executeQuery(new UtxoByAddressQuery(Era.Alonzo, new Address("addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y")));

        Mono<UtxoByAddressQueryResult> queryResultMono = queryClient.executeQuery(new UtxoByAddressQuery(Era.Alonzo, new Address("addr_test1vpfwv0ezc5g8a4mkku8hhy3y3vp92t7s3ul8g778g5yegsgalc6gc")));

        CountDownLatch countDownLatch = new CountDownLatch(1);
        queryResultMono.subscribe(result -> {
            System.out.println(result);
            countDownLatch.countDown();
        });

        countDownLatch.await(20, TimeUnit.SECONDS);
    }

}
