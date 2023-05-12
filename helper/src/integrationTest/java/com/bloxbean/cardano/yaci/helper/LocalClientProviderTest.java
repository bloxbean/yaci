package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.SystemStartQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.SystemStartResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class LocalClientProviderTest extends BaseTest {

    private LocalClientProvider localQueryProvider;
    private LocalStateQueryClient localStateQueryClient;

    LocalClientProviderTest(LocalStateQueryClient localStateQueryClient) {
        this.localStateQueryClient = localStateQueryClient;
    }

    @BeforeEach
    public void setup() {
        this.localQueryProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
        localQueryProvider.start();
    }

    @AfterEach
    public void tearDown() {
        this.localQueryProvider.shutdown();
    }

    @Test
    void startTimeQuery() {
        Mono<SystemStartResult> queryResultMono = localQueryProvider.getLocalStateQueryClient().executeQuery(new SystemStartQuery());
        SystemStartResult result = queryResultMono.block();
        System.out.println(result);
        assertThat(result.getYear()).isEqualTo(2022);
    }

}
