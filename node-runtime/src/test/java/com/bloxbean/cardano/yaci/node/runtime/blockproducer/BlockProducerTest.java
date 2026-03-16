package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yaci.node.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class BlockProducerTest {

    private InMemoryChainState chainState;
    private MemPool memPool;
    private ScheduledExecutorService scheduler;
    private BlockProducer blockProducer;

    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
        memPool = new DefaultMemPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (blockProducer != null && blockProducer.isRunning()) {
            blockProducer.stop();
        }
        scheduler.shutdownNow();
    }

    @Test
    void start_producesGenesisBlock() {
        blockProducer = createBlockProducer(2000, false);
        blockProducer.start();

        // Genesis block should have been produced
        ChainTip tip = chainState.getTip();
        assertNotNull(tip, "Chain tip should exist after start");
        assertEquals(0, tip.getBlockNumber());
        assertNotNull(tip.getBlockHash());
    }

    @Test
    void nonLazyMode_producesEmptyBlocksOnSchedule() throws Exception {
        blockProducer = createBlockProducer(200, false);
        blockProducer.start();

        // Wait for at least 2 scheduled blocks (genesis + 2 ticks)
        Thread.sleep(600);

        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertThat(tip.getBlockNumber()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void lazyMode_skipsWhenMempoolEmpty() throws Exception {
        blockProducer = createBlockProducer(200, true);
        blockProducer.start();

        // Wait for a few ticks with empty mempool
        Thread.sleep(500);

        // In lazy mode, only genesis should exist (block 0)
        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertEquals(0, tip.getBlockNumber(), "Lazy mode should not produce blocks with empty mempool");
    }

    @Test
    void lazyMode_producesWhenMempoolHasTransaction() throws Exception {
        blockProducer = createBlockProducer(200, true);
        blockProducer.start();

        // Add a transaction to the mempool
        memPool.addTransaction(buildSampleTxCbor());

        // Wait for the block producer to pick it up
        Thread.sleep(500);

        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertThat(tip.getBlockNumber()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void restart_resumesFromExistingTip() {
        // Pre-populate chain state using a builder
        DevnetBlockBuilder builder = new DevnetBlockBuilder();
        var genesis = builder.buildBlock(0, 0, null, java.util.List.of());
        chainState.storeBlock(genesis.blockHash(), 0L, 0L, genesis.blockCbor());
        chainState.storeBlockHeader(genesis.blockHash(), 0L, 0L, genesis.wrappedHeaderCbor());

        var block1 = builder.buildBlock(1, 10, genesis.blockHash(), java.util.List.of());
        chainState.storeBlock(block1.blockHash(), 1L, 10L, block1.blockCbor());
        chainState.storeBlockHeader(block1.blockHash(), 1L, 10L, block1.wrappedHeaderCbor());

        // Now start block producer — it should resume from block 1
        blockProducer = createBlockProducer(2000, false);
        blockProducer.start();

        // Verify it didn't overwrite existing data
        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        // Tip should still be block 1 (no tick has fired yet since interval is 2000ms)
        assertEquals(1, tip.getBlockNumber());
    }

    @Test
    void mempoolDrain_transactionsAppearInProducedBlock() throws Exception {
        blockProducer = createBlockProducer(300, false);
        blockProducer.start();

        // Wait for genesis block
        Thread.sleep(100);

        // Add transactions
        memPool.addTransaction(buildSampleTxCbor());
        memPool.addTransaction(buildSampleTxCbor());

        // Wait for next block to be produced
        Thread.sleep(500);

        // Mempool should be drained
        assertTrue(memPool.isEmpty(), "Mempool should be empty after block production");
    }

    @Test
    void stop_stopsProduction() throws Exception {
        blockProducer = createBlockProducer(100, false);
        blockProducer.start();
        Thread.sleep(300);

        blockProducer.stop();
        assertFalse(blockProducer.isRunning());

        // Record tip after stop
        ChainTip tipAtStop = chainState.getTip();
        Thread.sleep(300);

        // Tip should not have advanced
        ChainTip tipAfterWait = chainState.getTip();
        assertEquals(tipAtStop.getBlockNumber(), tipAfterWait.getBlockNumber());
    }

    @Test
    void start_withGenesisFunds_producesEmptyGenesisBlock() {
        GenesisConfig genesisConfig;

        // Write a shelley-genesis.json format temp file with initialFunds
        try {
            java.io.File tempFile = java.io.File.createTempFile("shelley-genesis", ".json");
            tempFile.deleteOnExit();
            java.util.Map<String, Object> genesis = new java.util.LinkedHashMap<>();
            java.util.Map<String, Long> funds = new java.util.LinkedHashMap<>();
            funds.put("60" + "aa".repeat(28), 10_000_000_000L);
            funds.put("60" + "bb".repeat(28), 5_000_000_000L);
            genesis.put("initialFunds", funds);
            genesis.put("networkMagic", 42);
            genesis.put("epochLength", 600);
            genesis.put("slotLength", 1);
            genesis.put("systemStart", "2024-01-01T00:00:00Z");
            genesis.put("maxLovelaceSupply", 45000000000000000L);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writeValue(tempFile, genesis);

            genesisConfig = GenesisConfig.load(tempFile.getAbsolutePath(), null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(genesisConfig.hasInitialFunds()).isTrue();
        assertThat(genesisConfig.getInitialFunds()).hasSize(2);

        blockProducer = new BlockProducer(
                chainState, memPool, null, new NoopEventBus(), scheduler,
                2000, false, System.currentTimeMillis(), 1000, genesisConfig);
        blockProducer.start();

        // Genesis block should exist but be empty (no transactions)
        // Genesis UTXOs are stored directly in UTXO store by YaciNode, not embedded in block
        ChainTip tip = chainState.getTip();
        assertNotNull(tip);
        assertEquals(0, tip.getBlockNumber());

        byte[] blockCbor = chainState.getBlock(tip.getBlockHash());
        assertNotNull(blockCbor);
        com.bloxbean.cardano.yaci.core.model.Block block =
                com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer.INSTANCE.deserialize(blockCbor);
        assertThat(block.getTransactionBodies()).isEmpty();
    }

    private BlockProducer createBlockProducer(int blockTimeMillis, boolean lazy) {
        return new BlockProducer(
                chainState, memPool, null, new NoopEventBus(), scheduler,
                blockTimeMillis, lazy, System.currentTimeMillis(), 1000, null, new DummyTransactionValidationService(null, null), null);
    }

    private byte[] buildSampleTxCbor() {
        Map txBody = new Map();
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(0));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        output.put(new UnsignedInteger(1), new UnsignedInteger(1000000));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200000));

        Map witnesses = new Map();

        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(SimpleValue.TRUE);
        tx.add(SimpleValue.NULL);

        return CborSerializationUtil.serialize(tx);
    }

    class DummyTransactionValidationService extends TransactionValidationService {

        public DummyTransactionValidationService(TransactionValidator validator, UtxoState utxoState) {
            super(validator, utxoState);
        }

        @Override
        public ValidationResult validate(byte[] txCbor) {
            return ValidationResult.success();
        }

        @Override
        public ValidationResult validate(byte[] txCbor, Function<Outpoint, Utxo> resolver) {
            return  ValidationResult.success();
        }
    }
}
