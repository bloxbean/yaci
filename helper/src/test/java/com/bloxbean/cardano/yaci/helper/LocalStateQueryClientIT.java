package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
@Slf4j
class LocalStateQueryClientIT extends BaseTest {

    private LocalClientProvider localQueryProvider;
    private LocalStateQueryClient localStateQueryClient;
    private Era era;

    @BeforeEach
    public void setup() {
        this.era = Era.Babbage;
        this.localQueryProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
        this.localStateQueryClient = localQueryProvider.getLocalStateQueryClient();
        localQueryProvider.start();
    }

    @AfterEach
    public void tearDown() {
        this.localQueryProvider.shutdown();
    }

    @Test
    void startTimeQuery() throws InterruptedException {
        Mono<SystemStartResult> queryResultMono = localStateQueryClient.executeQuery(new SystemStartQuery());
        Mono<SystemStartResult> mono = queryResultMono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(startTime -> startTime != null && startTime.getYear() == 2022)
                .expectComplete()
                .verify();
    }

    @Test
    void blockHeighQuery() {
        Mono<BlockHeightQueryResult> blockHeightQueryMono = localStateQueryClient.executeQuery(new BlockHeightQuery());
        Mono<BlockHeightQueryResult> mono = blockHeightQueryMono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(blockHeightQueryResult -> blockHeightQueryResult.getBlockHeight() > 19000)
                .expectComplete()
                .verify();
    }

    @Test
    void blockHeighQuery_whenManualAcquire() {
        localStateQueryClient.acquire().block(Duration.ofSeconds(15));
        Mono<BlockHeightQueryResult> blockHeightQueryMono = localStateQueryClient.executeQuery(new BlockHeightQuery());
        Mono<BlockHeightQueryResult> mono = blockHeightQueryMono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(blockHeightQueryResult -> blockHeightQueryResult.getBlockHeight() > 19000)
                .expectComplete()
                .verify();
    }

    @Test
    void chainPointQuery() {
        Mono<ChainPointQueryResult> chainPointQueryMono = localStateQueryClient.executeQuery(new ChainPointQuery());
        Mono<ChainPointQueryResult> mono = chainPointQueryMono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(chainPointQueryResult -> chainPointQueryResult.getChainPoint().getSlot() > 1000)
                .expectComplete()
                .verify();

        //Reacquire
        Mono<Optional<Point>> reAcquireMono = localStateQueryClient.reAcquire();
        reAcquireMono.block();

        Mono<BlockHeightQueryResult> blockHeightQueryMono = localStateQueryClient.executeQuery(new BlockHeightQuery());
        blockHeightQueryMono = blockHeightQueryMono.log();

        StepVerifier
                .create(blockHeightQueryMono)
                .expectNextMatches(blockHeightQueryResult -> blockHeightQueryResult.getBlockHeight() > 1000)
                .expectComplete()
                .verify();
    }

    @Test
    void protocolParameters() {
        Mono<CurrentProtocolParamQueryResult> mono = localStateQueryClient.executeQuery(new CurrentProtocolParamsQuery(era));
        mono = mono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(protoParams -> protoParams.getProtocolParams().getCollateralPercent() == 150
                        && protoParams.getProtocolParams().getMaxCollateralInputs().equals(3))
                .expectComplete()
                .verify();
    }

    @Test
    void epochNoQuery() {
        Mono<EpochNoQueryResult> mono = localStateQueryClient.executeQuery(new EpochNoQuery(era));
        mono = mono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(epochNoQueryResult -> epochNoQueryResult.getEpochNo() > 32)
                .expectComplete()
                .verify();
    }

    @Test
    void utxoByAddress() {
        Mono<UtxoByAddressQueryResult> mono = localStateQueryClient.executeQuery(new UtxoByAddressQuery(era, new Address("addr_test1vpfwv0ezc5g8a4mkku8hhy3y3vp92t7s3ul8g778g5yegsgalc6gc")));
        mono = mono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(utxoByAddressQueryResult -> utxoByAddressQueryResult.getUtxoList().size() > 0)
                .expectComplete()
                .verify();
    }

    @Test
    void acquireReacquireAndQuery() {
        localStateQueryClient.acquire().block(Duration.ofSeconds(15));
        Mono<CurrentProtocolParamQueryResult> mono = localStateQueryClient.executeQuery(new CurrentProtocolParamsQuery(Era.Babbage));
        CurrentProtocolParamQueryResult protocolParams = mono.block(Duration.ofSeconds(8));
        log.info("Protocol Params >> " + protocolParams);

        assertThat(protocolParams.getProtocolParams().getCollateralPercent()).isEqualTo(150);
        assertThat(protocolParams.getProtocolParams().getMaxCollateralInputs()).isEqualTo(3);

        //Re-Acquire
        localStateQueryClient.reAcquire().block(Duration.ofSeconds(15));

        Mono<EpochNoQueryResult> queryResultMono = localStateQueryClient.executeQuery(new EpochNoQuery(era));
        EpochNoQueryResult epochNoQueryResult = queryResultMono.block(Duration.ofSeconds(20));

        log.info("Epoch >> " + epochNoQueryResult.getEpochNo());
        assertThat(epochNoQueryResult.getEpochNo()).isGreaterThanOrEqualTo(32);

        //release
        localStateQueryClient.release().block();

        //Automatically acquire if in Idle state
        queryResultMono = localStateQueryClient.executeQuery(new EpochNoQuery(era));
        epochNoQueryResult = queryResultMono.block(Duration.ofSeconds(20));
    }

//    @Test
    void nestedCalls() throws InterruptedException {
        Mono<CurrentProtocolParamQueryResult> mono= localQueryProvider.getTxMonitorClient()
                .acquireAndGetMempoolSizeAndCapacity()
                .filter(mempoolStatus -> mempoolStatus.getNumberOfTxs() == 0)
                .flatMap(mempoolStatus -> localStateQueryClient.acquire())
                .publishOn(Schedulers.boundedElastic())
                .map(point -> {
                    CurrentProtocolParamQueryResult currentProtocolParamQueryResult =
                            (CurrentProtocolParamQueryResult) localStateQueryClient.executeQuery(new CurrentProtocolParamsQuery(era)).block(Duration.ofSeconds(10));
                    System.out.println(currentProtocolParamQueryResult);
                    return currentProtocolParamQueryResult;
                });

        mono = mono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(protoParams -> protoParams.getProtocolParams().getMaxBlockSize() > 20000)
                .expectComplete()
                .verify();
    }

    @Test
    void stakeDistributionQuery() {
        Mono<StakeDistributionQueryResult> mono = localStateQueryClient.executeQuery(new StakeDistributionQuery());
        StakeDistributionQueryResult result = mono.block();

        System.out.println(result);
        assertThat(result.getStakeDistributionMap()).hasSizeGreaterThan(0);
    }

    @Test
    void stakeSnapshotsQuery() {
        Mono<StakeSnapshotQueryResult> mono = localStateQueryClient.executeQuery(new StakeSnapshotQuery("032a04334a846fdf542fd5633c9b3928998691b8276e004facbc8af1"));
        StakeSnapshotQueryResult result = mono.block();

        System.out.println(result);
    }

    @Test
    void stakePoolParamQuery() {
        Mono<StakePoolParamQueryResult> mono = localStateQueryClient.executeQuery(new StakePoolParamsQuery(List.of("032a04334a846fdf542fd5633c9b3928998691b8276e004facbc8af1",
                "0a4ed3c5cc11a044cff16f7045588c9b6f6c98f7154026a3a3f55f24")));

        StakePoolParamQueryResult result = mono.block(Duration.ofSeconds(5));
        System.out.println(result);
    }

    @Test
    void poolDistrQuery() {
        Mono<PoolDistrQueryResult> mono = localStateQueryClient.executeQuery(new PoolDistrQuery(List.of("032a04334a846fdf542fd5633c9b3928998691b8276e004facbc8af1")));

        PoolDistrQueryResult result = mono.block();
        System.out.println(result);
    }

//    @Test
    void epochStateQuery() {
        Mono<EpochStateQueryResult> mono = localStateQueryClient.executeQuery(new EpochStateQuery());
        EpochStateQueryResult result = mono.block();

        System.out.println(result);
    }

    @Test
    void genesisConfigQuery() {
        Mono<GenesisConfigQueryResult> mono = localStateQueryClient.executeQuery(new GenesisConfigQuery(Era.Babbage));
        GenesisConfigQueryResult result = mono.block(Duration.ofSeconds(5));

        LocalDate localDate = LocalDate.ofYearDay(2017, 30);
        LocalTime localTime = LocalTime.ofNanoOfDay(0 / 1000);
        assertThat(result.getSystemStartTime()).isAfter(LocalDateTime.of(localDate, localTime));
        assertThat(result.getNetworkMagic()).isNotZero();
        assertThat(result.getNetworkId()).isEqualTo(0);
        assertThat(result.getSlotsPerKesPeriod()).isEqualTo(129600);
        assertThat(result.getActiveSlotsCoeff()).isEqualTo(0.05);
        assertThat(result.getSecurityParam()).isEqualTo(2160);
        assertThat(result.getEpochLength()).isEqualTo(432000);
        assertThat(result.getMaxKESEvolutions()).isEqualTo(62);
        assertThat(result.getMaxLovelaceSupply()).isEqualTo(45000000000000000L);

    }
}
