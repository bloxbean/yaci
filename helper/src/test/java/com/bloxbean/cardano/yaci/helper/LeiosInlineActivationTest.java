package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.common.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeiosInlineActivationTest {
    private static final long MAINNET = Constants.MAINNET_PROTOCOL_MAGIC;
    private static final long PREPROD = 1;
    private static final long MUSASHI = 164;

    @AfterEach
    void restoreDefault() {
        YaciConfig.INSTANCE.setInlineEndorserBlockTxs(true);
    }

    @Test
    void inliningIsOnByDefaultOnlyOnMusashi() {
        LeiosConfig auto = LeiosConfig.defaultConfig();

        assertTrue(YaciConfig.INSTANCE.isInlineEndorserBlockTxs());
        assertTrue(auto.shouldInlineEndorserBlockTxs(MUSASHI));
        assertFalse(auto.shouldInlineEndorserBlockTxs(MAINNET));
        assertFalse(auto.shouldInlineEndorserBlockTxs(PREPROD));
    }

    @Test
    void inliningCanBeSwitchedOffGloballyOrPerConnection() {
        assertFalse(LeiosConfig.disabled().shouldInlineEndorserBlockTxs(MUSASHI));

        YaciConfig.INSTANCE.setInlineEndorserBlockTxs(false);
        assertFalse(LeiosConfig.defaultConfig().shouldInlineEndorserBlockTxs(MUSASHI));
        assertFalse(LeiosConfig.builder().mode(LeiosConfig.Mode.ENABLED).build().shouldInlineEndorserBlockTxs(MUSASHI));
    }

    @Test
    void mainnetBlockSyncKeepsTheApplicationListener() throws Exception {
        //No assembler (and no extra Leios connection) is created for a non-Leios network
        BlockSync blockSync = new BlockSync("localhost", 3001, MAINNET, null);
        var method = BlockSync.class.getDeclaredMethod("completeBlocksListener",
                com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener.class,
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point.class);
        method.setAccessible(true);
        var app = new com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener() {
        };

        assertTrue(method.invoke(blockSync, app, null) == app);
    }
}
