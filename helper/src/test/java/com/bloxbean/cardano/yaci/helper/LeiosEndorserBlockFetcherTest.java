package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosEndorserBlockFetcherTest {

    @Test
    void unsupportedInstanceFailsLoudWithProtocolRationale() {
        LeiosEndorserBlockFetcher fetcher = LeiosEndorserBlockFetcher.unsupported();

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> fetcher.fetchClosures(List.of(new LeiosPoint(1234, new byte[32]))));

        assertTrue(e.getMessage().contains("MsgLeiosMultiBlockRequest"));
        assertTrue(e.getMessage().contains("ADR 0012"));
    }
}
