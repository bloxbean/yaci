package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class LocalStateQueryClientIT extends BaseTest {

    private LocalClientProvider localQueryProvider;
    private LocalStateQueryClient localStateQueryClient;
    private Era era;

    @BeforeEach
    public void setup() {
        this.era = Era.Conway;
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
        Mono<CurrentProtocolParamQueryResult> mono = null;
        mono = localStateQueryClient.executeQuery(new CurrentProtocolParamsQuery(Era.Conway));

        mono = mono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(protoParams -> protoParams.getProtocolParams().getCollateralPercent() == 150
                        && protoParams.getProtocolParams().getMaxCollateralInputs().equals(3)
                        && protoParams.getProtocolParams().getMaxTxExMem().equals(BigInteger.valueOf(14000000))
                )
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
        Mono<UtxoByAddressQueryResult> mono = localStateQueryClient.executeQuery(new UtxoByAddressQuery(new Address("addr_test1vpfwv0ezc5g8a4mkku8hhy3y3vp92t7s3ul8g778g5yegsgalc6gc")));
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
        Mono<CurrentProtocolParamQueryResult> mono = localStateQueryClient.executeQuery(new CurrentProtocolParamsQuery(Era.Conway));
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
        Mono<CurrentProtocolParamQueryResult> mono = localQueryProvider.getTxMonitorClient()
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
        StakeSnapshotQueryResult result = mono.block(Duration.ofSeconds(5));

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
        Mono<GenesisConfigQueryResult> mono = localStateQueryClient.executeQuery(new GenesisConfigQuery(Era.Conway));
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

    @Test
    void delegationRewardsQuery() {
        //Mix of regular staking key and script staking key to test if the sorting during input works, otherwise the request will fail
        Set<Address> stakeAddresses = new LinkedHashSet<>(List.of(
                new Address("stake_test1upfjudlteayz2ayenwkyqy77zxf92k69f28g0vur0rqmg3sjr95v7"),
                new Address("stake_test1up97ct2wt8jqlly2cnkhuwc7tvevmjpp7h6ts3rucpksy8c8cnspn"),
                new Address("stake_test1upkmrqs6qjmhwc89l3fzfvzf7vd0xl8zd5h76pah9kv4npc2ql0vf"),
                new Address("stake_test1upzr8x6my8u5qfx74vgjsfexf2xf3qrfnmd6uahvu866xugj26s8g"),
                new Address("stake_test1uret0j5crrwvvm6e94p20yfgyswwzvrr3hd8cz7n6h4qensv6502k"),
                new Address("stake_test17qag3rt979nep9g2wtdwu8mr4gz6m4kjdpp5zp705km8wys6r5wgh"),//script 1,
                new Address("stake_test17rgr608tyvgawu5ja328xy523rrj75xx5x492gltauc649czsnx8t"), //script 2
                new Address("stake_test17p6ffq4jle9vw9daatwx0kclgfsqfqltkxgnl2q2yeq35cc9du8ml"), //script 3
                new Address("stake_test17rqvxk8m24yfl3qveuj0r32tktk3eymjvlf2cujtdavqfvc4xpaer") //script 4
        ));

        stakeAddresses.forEach(address -> System.out.println("Input Address >> " + address.toBech32()));

        Mono<DelegationsAndRewardAccountsResult> mono = localStateQueryClient.executeQuery(new DelegationsAndRewardAccountsQuery(stakeAddresses));
        DelegationsAndRewardAccountsResult result = mono.block();

        Map<Address, String> delegations = result.getDelegations();
        Map<Address, BigInteger> rewards = result.getRewards();

        System.out.println("######### Delegations and Rewards ########");
        delegations.forEach((address, poolId) -> {
            System.out.println("Delegation >> " + address.toBech32() + " : " + poolId);
        });

        rewards.forEach((address, reward) -> {
            System.out.println("Reward >> " + address.toBech32() + " : " + reward);
        });

        assertThat(delegations).hasSizeGreaterThanOrEqualTo(5); //only 5 regular staking keys has delegations
        assertThat(rewards).hasSizeGreaterThanOrEqualTo(5); //only 5 regular staking keys has rewards
    }

    @Test
    void accountStateQuery() {
        Mono<AccountStateQueryResult> mono = localStateQueryClient.executeQuery(new AccountStateQuery(Era.Conway));

        mono = mono.log();

        StepVerifier
                .create(mono)
                .expectNextMatches(accountState -> accountState.getTreasury() != null && accountState.getReserves() != null)
                .expectComplete()
                .verify();
    }

    @Test
    void dRepStakeDistributionQuery() {
        Mono<DRepStakeDistributionQueryResult> mono = localStateQueryClient.executeQuery(
                new DRepStakeDistributionQuery(
                        Era.Conway,
                        List.of(
                                DRep.addrKeyHash("23bc63ced4e40b22dd4e6051c258ba38d5679d81e33f34fe5e5cdb4d")
                               , DRep.addrKeyHash("fa6a8dc2635dddcf9af495cb144f7eb4ff845866fe48695ad7cb65d3")
                        ))
        );

        mono = mono.log();

        DRepStakeDistributionQueryResult result = mono.block(Duration.ofSeconds(5));
        assertThat(result.getDRepStakeMap()).isNotNull();
    }

    @Test
    void govStateQuery() {
        Mono<GovStateQueryResult> mono = localStateQueryClient.executeQuery(new GovStateQuery(Era.Conway));
        mono = mono.log();

        GovStateQueryResult result = mono.block(Duration.ofSeconds(10));
        assertThat(result.getCommittee()).isNotNull();
        assertThat(result.getCurrentPParams()).isNotNull();
    }

    @Test
    void constitutionQuery() {
        Mono<ConstitutionQueryResult> mono = localStateQueryClient.executeQuery(new ConstitutionQuery(Era.Conway));
        mono = mono.log();

        ConstitutionQueryResult result = mono.block(Duration.ofSeconds(5));
        assertThat(result).isNotNull();
    }

    @Test
    void dRepStateQuery() {
        Mono<DRepStateQueryResult> mono = localStateQueryClient.executeQuery(new DRepStateQuery(
                Era.Conway,
                List.of(
                        Credential
                                .builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash("23bc63ced4e40b22dd4e6051c258ba38d5679d81e33f34fe5e5cdb4d")
                                .build(),
                        Credential
                                .builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash("fa6a8dc2635dddcf9af495cb144f7eb4ff845866fe48695ad7cb65d3")
                                .build()
                ))
        );
        mono = mono.log();

        DRepStateQueryResult result = mono.block(Duration.ofSeconds(5));
        assertThat(result.getDRepStates()).hasSizeGreaterThan(0);
    }

    @Test
    void SPOStakeDistrQuery() {
        Mono<SPOStakeDistributionQueryResult> mono = localStateQueryClient.executeQuery(new SPOStakeDistributionQuery(
                List.of("032a04334a846fdf542fd5633c9b3928998691b8276e004facbc8af1")
                )
        );

        SPOStakeDistributionQueryResult result = mono.block();
        assertThat(result.getSpoStakeMap()).hasSize(1);
    }
}
