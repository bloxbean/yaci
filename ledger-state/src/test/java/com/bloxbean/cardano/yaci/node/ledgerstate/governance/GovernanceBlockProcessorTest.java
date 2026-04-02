package com.bloxbean.cardano.yaci.node.ledgerstate.governance;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore.DeltaOp;
import com.bloxbean.cardano.yaci.node.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GovernanceBlockProcessor — block-level governance processing.
 * Uses real RocksDB to verify donation accumulation and WriteBatch correctness.
 */
class GovernanceBlockProcessorTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore store;
    private GovernanceBlockProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = rocks.governanceStore();
        // paramProvider with dummy values — donations don't need protocol params
        var paramProvider = new com.bloxbean.cardano.yaci.node.api.EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long e) { return BigInteger.ZERO; }
            @Override public BigInteger getPoolDeposit(long e) { return BigInteger.ZERO; }
        };
        processor = new GovernanceBlockProcessor(store, paramProvider);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    // ===== Donation Accumulation =====

    @Test
    @DisplayName("Single donation in a block is accumulated correctly")
    void singleDonation_accumulated() throws Exception {
        var tx = TransactionBody.builder()
                .txHash("aaaa000000000000000000000000000000000000000000000000000000000000")
                .donation(BigInteger.valueOf(5_000_000))
                .build();
        var block = Block.builder()
                .transactionBodies(List.of(tx))
                .build();

        try (WriteBatch batch = new WriteBatch()) {
            processor.processBlock(block, 101834571L, 239, batch, new ArrayList<>());
            commit(batch);
        }

        assertThat(store.getEpochDonations(239)).isEqualTo(BigInteger.valueOf(5_000_000));
    }

    @Test
    @DisplayName("Multiple donations in SAME block are accumulated correctly (bug fix regression)")
    void multipleDonationsInSameBlock_accumulated() throws Exception {
        // This is the exact scenario that was broken before the fix:
        // Two txs in the same block at slot 101834571 (epoch 239 on preprod)
        // Previously only the last donation survived due to WriteBatch visibility issue.
        var tx1 = TransactionBody.builder()
                .txHash("4f70a86476cee998a9efc546214b9029649759b4bc0fc46b59779681278403e6")
                .donation(BigInteger.valueOf(4_000_000))
                .build();
        var tx2 = TransactionBody.builder()
                .txHash("877ea94b648bf1922fff6dfc16961ffc0b0eaed64e76fe1eb7abfca50f988099")
                .donation(BigInteger.valueOf(5_000_000))
                .build();
        var block = Block.builder()
                .transactionBodies(List.of(tx1, tx2))
                .build();

        try (WriteBatch batch = new WriteBatch()) {
            processor.processBlock(block, 101834571L, 239, batch, new ArrayList<>());
            commit(batch);
        }

        // Both donations must be accumulated: 4M + 5M = 9M
        assertThat(store.getEpochDonations(239)).isEqualTo(BigInteger.valueOf(9_000_000));
    }

    @Test
    @DisplayName("Donations across multiple blocks in same epoch accumulate")
    void donationsAcrossBlocks_accumulate() throws Exception {
        // Block 1: 4M donation
        var block1 = Block.builder()
                .transactionBodies(List.of(TransactionBody.builder()
                        .txHash("aaaa000000000000000000000000000000000000000000000000000000000001")
                        .donation(BigInteger.valueOf(4_000_000)).build()))
                .build();

        try (WriteBatch batch = new WriteBatch()) {
            processor.processBlock(block1, 101834571L, 239, batch, new ArrayList<>());
            commit(batch);
        }

        // Block 2: 2M + 1M donations
        var block2 = Block.builder()
                .transactionBodies(List.of(
                        TransactionBody.builder()
                                .txHash("bbbb000000000000000000000000000000000000000000000000000000000002")
                                .donation(BigInteger.valueOf(2_000_000)).build(),
                        TransactionBody.builder()
                                .txHash("cccc000000000000000000000000000000000000000000000000000000000003")
                                .donation(BigInteger.valueOf(1_000_000)).build()))
                .build();

        try (WriteBatch batch = new WriteBatch()) {
            processor.processBlock(block2, 101904092L, 239, batch, new ArrayList<>());
            commit(batch);
        }

        // Total: 4M + 2M + 1M = 7M
        assertThat(store.getEpochDonations(239)).isEqualTo(BigInteger.valueOf(7_000_000));
    }

    @Test
    @DisplayName("Invalid transactions are skipped — their donations not counted")
    void invalidTx_donationSkipped() throws Exception {
        var validTx = TransactionBody.builder()
                .txHash("aaaa000000000000000000000000000000000000000000000000000000000001")
                .donation(BigInteger.valueOf(5_000_000)).build();
        var invalidTx = TransactionBody.builder()
                .txHash("bbbb000000000000000000000000000000000000000000000000000000000002")
                .donation(BigInteger.valueOf(3_000_000)).build();

        var block = Block.builder()
                .transactionBodies(List.of(validTx, invalidTx))
                .invalidTransactions(List.of(1)) // index 1 is invalid
                .build();

        try (WriteBatch batch = new WriteBatch()) {
            processor.processBlock(block, 101834571L, 239, batch, new ArrayList<>());
            commit(batch);
        }

        // Only 5M from valid tx, not 8M
        assertThat(store.getEpochDonations(239)).isEqualTo(BigInteger.valueOf(5_000_000));
    }

    @Test
    @DisplayName("Block with no donations produces zero epoch donations")
    void noDonations_zeroDonations() throws Exception {
        var tx = TransactionBody.builder()
                .txHash("aaaa000000000000000000000000000000000000000000000000000000000001")
                .build(); // no donation

        var block = Block.builder().transactionBodies(List.of(tx)).build();

        try (WriteBatch batch = new WriteBatch()) {
            processor.processBlock(block, 101834571L, 239, batch, new ArrayList<>());
            commit(batch);
        }

        assertThat(store.getEpochDonations(239)).isEqualTo(BigInteger.ZERO);
    }
}
