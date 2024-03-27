package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.NetworkType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class N2NTxSubmissionTest {

    public static void main(String[] args) {
        TxSubmissionClient txSubmissionClient = new TxSubmissionClient("delta.relay.easy1staking.com", 30000, NetworkType.MAINNET.getN2NVersionTable());
        txSubmissionClient.start(c -> {
            log.info("c: {}", c.getClass());
        });
    }

}
