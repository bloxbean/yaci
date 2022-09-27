package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.BaseTest;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INT_TEST", matches = "true")
@Slf4j
class LocalStateQueryClientTest extends BaseTest {

    LocalStateQueryClient queryClient;

    @BeforeEach
    void setup() {
        queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
        queryClient.start();
    }

    @AfterEach
    void tearDown() {
        queryClient.shutdown();
    }

    @Test
    void startTimeQuery() {
        Mono<SystemStartResult> queryResultMono = queryClient.executeQuery(new SystemStartQuery());
        SystemStartResult systemStartResult = queryResultMono.block(Duration.ofSeconds(20));

        log.info("SystemStartQuery >> " + systemStartResult);
        assertNotNull(systemStartResult);
        assertEquals(systemStartResult.getYear(), 2022);
    }

    @Test
    void blockHeighQuery() {
        Mono<BlockHeightQueryResult> blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
        BlockHeightQueryResult blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(20));

        log.info("BlockHeight >> " + blockHeightQueryResult);
        assertTrue(blockHeightQueryResult.getBlockHeight() > 19000);
    }

    @Test
    void chainPoint() {
        Mono<ChainPointQueryResult> chainPointQueryMono = queryClient.executeQuery(new ChainPointQuery());
        ChainPointQueryResult chainPointQueryResult = chainPointQueryMono.block(Duration.ofSeconds(8));
        log.info("ChainPoint >> " + chainPointQueryResult);

        Mono<Point> reAcquireMono = queryClient.reAcquire();
        reAcquireMono.block();

        Mono<BlockHeightQueryResult> blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
        BlockHeightQueryResult blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(20));

        log.info("BlockHeight >> " + blockHeightQueryResult);

        //TODO -- test
//        queryClient.release().block();
//        queryClient.acquire(chainPointQueryResult.getChainPoint()).block();

        blockHeightQueryMono = queryClient.executeQuery(new BlockHeightQuery());
        blockHeightQueryResult = blockHeightQueryMono.block(Duration.ofSeconds(20));

        log.info("BlockHeight >> " + blockHeightQueryResult);
    }

    @Test
    void protocolParameters() {
        Mono<CurrentProtocolParamQueryResult> mono = queryClient.executeQuery(new CurrentProtocolParamsQuery(Era.Alonzo));
        CurrentProtocolParamQueryResult protocolParams = mono.block(Duration.ofSeconds(8));
        log.info("Protocol Params >> " + protocolParams);

        assertThat(protocolParams.getProtocolParams().getCollateralPercent()).isEqualTo(150);
        assertThat(protocolParams.getProtocolParams().getMaxCollateralInputs()).isEqualTo(3);
    }

    @Test
    void epochNoQuery() {
        Mono<EpochNoQueryResult> queryResultMono = queryClient.executeQuery(new EpochNoQuery(Era.Alonzo));
        EpochNoQueryResult epochNoQueryResult = queryResultMono.block(Duration.ofSeconds(20));

        log.info("Epoch >> " + epochNoQueryResult.getEpochNo());
        assertThat(epochNoQueryResult.getEpochNo()).isGreaterThanOrEqualTo(49);
    }

    @Test
    void utxoByAddress() {
        Mono<UtxoByAddressQueryResult> queryResultMono = queryClient.executeQuery(new UtxoByAddressQuery(Era.Alonzo, new Address("addr_test1vpfwv0ezc5g8a4mkku8hhy3y3vp92t7s3ul8g778g5yegsgalc6gc")));
        UtxoByAddressQueryResult utxoByAddressQueryResult = queryResultMono.block(Duration.ofSeconds(20));

        log.info("Utxos >> " + utxoByAddressQueryResult.getUtxoList());
        assertThat(utxoByAddressQueryResult.getUtxoList()).hasSizeGreaterThan(0);
    }

}
