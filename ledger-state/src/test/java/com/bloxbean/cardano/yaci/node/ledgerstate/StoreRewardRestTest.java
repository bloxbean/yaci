package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.node.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DefaultAccountStateStore.storeRewardRest — verifying that deposit refunds
 * for deregistered addresses are correctly rejected (return false → goes to treasury).
 * Uses real RocksDB.
 */
class StoreRewardRestTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private DefaultAccountStateStore store;

    // Real preprod credential hash (28 bytes = 56 hex chars)
    static final String CRED_HASH = "1c46955f71c49a6c987104145d5a18154883f51c846c12a6a02dcd60";
    // Reward account = header byte (e0 = key + testnet) + cred hash
    static final String REWARD_ACCOUNT = "e0" + CRED_HASH;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(StoreRewardRestTest.class), true);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    /**
     * Register a stake credential by storing a PREFIX_ACCT entry.
     */
    private void registerCredential(int credType, String credHash) throws Exception {
        byte[] key = DefaultAccountStateStore.accountKey(credType, credHash);
        byte[] val = TestCborHelper.encodeStakeAccount(BigInteger.ZERO, BigInteger.valueOf(2_000_000));
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), key, val);
            commit(batch);
        }
    }

    // ===== Tests =====

    @Test
    @DisplayName("Registered address: storeRewardRest returns true (refund stored)")
    void registeredAddress_returnsTrue() throws Exception {
        registerCredential(0, CRED_HASH);

        try (WriteBatch batch = new WriteBatch()) {
            boolean stored = store.storeRewardRest(248,
                    DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                    REWARD_ACCOUNT, BigInteger.valueOf(100_000_000_000L), 247, 0,
                    batch, new ArrayList<>());
            assertThat(stored).isTrue();
        }
    }

    @Test
    @DisplayName("Deregistered address: storeRewardRest returns false (refund goes to treasury)")
    void deregisteredAddress_returnsFalse() throws Exception {
        // Don't register the credential → simulates deregistered address
        // This is the exact bug we fixed: proposal 84 on preprod expired at epoch 247,
        // proposer deregistered at epoch 240, deposit should go to treasury

        try (WriteBatch batch = new WriteBatch()) {
            boolean stored = store.storeRewardRest(248,
                    DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                    REWARD_ACCOUNT, BigInteger.valueOf(100_000_000_000L), 247, 0,
                    batch, new ArrayList<>());
            assertThat(stored).isFalse(); // false → treasury
        }
    }

    @Test
    @DisplayName("Null reward account: returns false")
    void nullRewardAccount_returnsFalse() throws Exception {
        try (WriteBatch batch = new WriteBatch()) {
            boolean stored = store.storeRewardRest(248,
                    DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                    null, BigInteger.valueOf(100_000_000_000L), 247, 0,
                    batch, new ArrayList<>());
            assertThat(stored).isFalse();
        }
    }

    @Test
    @DisplayName("Zero amount: returns false")
    void zeroAmount_returnsFalse() throws Exception {
        registerCredential(0, CRED_HASH);

        try (WriteBatch batch = new WriteBatch()) {
            boolean stored = store.storeRewardRest(248,
                    DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                    REWARD_ACCOUNT, BigInteger.ZERO, 247, 0,
                    batch, new ArrayList<>());
            assertThat(stored).isFalse();
        }
    }

    @Test
    @DisplayName("Script credential (header 0xf0): registered → returns true")
    void scriptCredential_registered_returnsTrue() throws Exception {
        registerCredential(1, CRED_HASH); // credType=1 for script

        // Header byte 0xf0 = script + testnet
        String scriptRewardAccount = "f0" + CRED_HASH;

        try (WriteBatch batch = new WriteBatch()) {
            boolean stored = store.storeRewardRest(248,
                    DefaultAccountStateStore.REWARD_REST_PROPOSAL_REFUND,
                    scriptRewardAccount, BigInteger.valueOf(50_000_000_000L), 247, 0,
                    batch, new ArrayList<>());
            assertThat(stored).isTrue();
        }
    }
}
