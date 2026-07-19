package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultMemPoolTest {

    private static final byte[] TX_BYTES = HexUtil.decodeHexString("84a30081825820b0753563f3fb5275647cacce8c9d24b9a58a65e3a4d5b3fc8c9fb68230eb4f1f010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a002dc6c0825839008c5bf0f2af6f1ef08bb3f6ec702dd16e1c514b7e1d12f7549b47db9f4d943c7af0aaec774757d4745d1a2c8dd3220e6ec2c9df23f757a2f81b0000000253ba77d6021a00029075a100818258209518c18103cbdab9c6e60b58ecc3e2eb439fef6519bb22570f391327381900a8584028ccef8e37ba03254556727ffa6969628feee642ab3cd58ec223be91c9cb19f9bfc7a6fe1c281ace676320a7cc8803fd4c0e51e09ec2312397fe01c23f424803f5f6");

    @Test
    void addTransactionReturnsEntryForNewTx() {
        var memPool = new DefaultMemPool();
        assertNotNull(memPool.addTransaction(TX_BYTES));
        assertEquals(1, memPool.size());
    }

    @Test
    void addTransactionReturnsNullForDuplicate() {
        // The same tx can be delivered by multiple N2N sessions (e.g. two peers connected
        // to the node server). The mempool must signal the duplicate with null so callers
        // publish MemPoolTransactionReceivedEvent exactly once per tx, not once per delivery.
        var memPool = new DefaultMemPool();
        assertNotNull(memPool.addTransaction(TX_BYTES));
        assertNull(memPool.addTransaction(TX_BYTES));
        assertEquals(1, memPool.size());
    }

}
