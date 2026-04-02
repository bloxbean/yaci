package com.bloxbean.cardano.yaci.node.ledgerstate.governance.epoch;

import com.bloxbean.cardano.yaci.node.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.TestCborHelper;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.GovernanceStateStore;
import com.bloxbean.cardano.yaci.node.ledgerstate.governance.model.DRepStateRecord;
import com.bloxbean.cardano.yaci.node.ledgerstate.test.TestRocksDBHelper;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DRepDistributionCalculator using real RocksDB.
 * Populates RocksDB with known delegations and DRep states, then verifies
 * the computed distribution matches expected values.
 */
class DRepDistributionCalculatorTest {

    @TempDir Path tempDir;
    private TestRocksDBHelper rocks;
    private GovernanceStateStore govStore;
    private DRepDistributionCalculator calculator;

    // Real preprod DRep hashes
    static final String DREP_A = "03ccae794affbe27a5f5f74da6266002db11daa6ae446aea783b972d";
    static final String DREP_B = "232ab6c11464fcdeb92b69f8f0958c1349b44a732b85248e4371caba";
    // Stake credential hashes
    static final String CRED1 = "aabbccdd11223344aabbccdd11223344aabbccdd11223344aabbccdd";
    static final String CRED2 = "11223344aabbccdd11223344aabbccdd11223344aabbccdd11223344";
    static final String CRED3 = "ffeeddcc99887766ffeeddcc99887766ffeeddcc99887766ffeeddcc";

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
        govStore = rocks.governanceStore();
        calculator = new DRepDistributionCalculator(rocks.db(), rocks.cfState(), rocks.cfSnapshot(), govStore);
    }

    @AfterEach
    void tearDown() { rocks.close(); }

    private void commit(WriteBatch batch) throws Exception {
        rocks.db().write(new WriteOptions(), batch);
    }

    /**
     * Store a DRep delegation: PREFIX_DREP_DELEG (0x03) + credType + credHash → drepType + drepHash + slot
     */
    private void storeDRepDelegation(int credType, String credHash, int drepType, String drepHash, long slot) throws Exception {
        byte[] hashBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hashBytes.length];
        key[0] = DefaultAccountStateStore.PREFIX_DREP_DELEG;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, hashBytes.length);

        byte[] val = TestCborHelper.encodeDRepDelegation(drepType, drepHash, slot, 0, 0);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), key, val);
            commit(batch);
        }
    }

    /**
     * Store a stake account: PREFIX_ACCT (0x01) + credType + credHash → reward + deposit
     */
    private void storeStakeAccount(int credType, String credHash, BigInteger reward, BigInteger deposit) throws Exception {
        byte[] hashBytes = HexUtil.decodeHexString(credHash);
        byte[] key = new byte[1 + 1 + hashBytes.length];
        key[0] = DefaultAccountStateStore.PREFIX_ACCT;
        key[1] = (byte) credType;
        System.arraycopy(hashBytes, 0, key, 2, hashBytes.length);

        byte[] val = TestCborHelper.encodeStakeAccount(reward, deposit);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(rocks.cfState(), key, val);
            commit(batch);
        }
    }

    /**
     * Register a DRep in governance store.
     */
    private void registerDRep(int drepType, String drepHash, int epoch, long slot) throws Exception {
        var state = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                epoch, null, epoch + 20, true, slot, 10, null);
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(drepType, drepHash, state, batch, new ArrayList<>());
            commit(batch);
        }
    }

    // ===== Tests =====

    @Test
    @DisplayName("Basic distribution: two delegators to one DRep")
    void basic_twoDelegatorsOneDRep() throws Exception {
        // Register DRep A
        registerDRep(0, DREP_A, 200, 84974395L);

        // Two credentials delegate to DRep A
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeDRepDelegation(0, CRED2, 0, DREP_A, 85000001L);

        // Both credentials have registered stake accounts
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));
        storeStakeAccount(0, CRED2, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        // Provide UTXO balances
        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(100_000_000),
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED2), BigInteger.valueOf(50_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist).containsKey(key);
        assertThat(dist.get(key)).isEqualTo(BigInteger.valueOf(150_000_000)); // 100M + 50M
    }

    @Test
    @DisplayName("Distribution includes rewards in stake amount")
    void distribution_includesRewards() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeStakeAccount(0, CRED1, BigInteger.valueOf(5_000_000), BigInteger.valueOf(2_000_000)); // reward=5M, deposit=2M

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(10_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist.get(key)).isEqualTo(BigInteger.valueOf(15_000_000)); // 10M utxo + 5M rewards
    }

    @Test
    @DisplayName("Distribution includes spendable reward_rest")
    void distribution_includesRewardRest() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(10_000_000));

        // reward_rest: "credType:credHash" → amount
        var rewardRest = Map.of("0:" + CRED1, BigInteger.valueOf(100_000_000_000L));

        var dist = calculator.calculate(230, utxoBalances, rewardRest);

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist.get(key)).isEqualTo(BigInteger.valueOf(100_010_000_000L)); // 10M + 100B reward_rest
    }

    @Test
    @DisplayName("Deregistered DRep excluded from distribution")
    void deregisteredDRep_excluded() throws Exception {
        // DRep with previousDeregistrationSlot AFTER registration → deregistered
        var deregState = new DRepStateRecord(
                BigInteger.valueOf(500_000_000_000L), null, null,
                200, null, 220, false, 84974395L, 10, 85000000L); // prevDeregSlot > regSlot
        try (WriteBatch batch = new WriteBatch()) {
            govStore.storeDRepState(0, DREP_B, deregState, batch, new ArrayList<>());
            commit(batch);
        }

        storeDRepDelegation(0, CRED1, 0, DREP_B, 85000001L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(100_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_B);
        assertThat(dist).doesNotContainKey(key);
    }

    @Test
    @DisplayName("Unregistered stake credential excluded from distribution")
    void unregisteredCredential_excluded() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED3, 0, DREP_A, 85000000L);
        // No storeStakeAccount for CRED3 → unregistered

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED3), BigInteger.valueOf(100_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        // DRep A should not be in distribution since its only delegator is unregistered
        assertThat(dist.getOrDefault(key, BigInteger.ZERO)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("Zero-balance DRep still included in distribution (with 0 amount)")
    void zeroBalanceDRep_included() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        // UTXO balance is 0
        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.ZERO);

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        var key = new DRepDistributionCalculator.DRepDistKey(0, DREP_A);
        assertThat(dist).containsKey(key);
        assertThat(dist.get(key)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("Multiple DReps with different delegators")
    void multipleDReps_differentDelegators() throws Exception {
        registerDRep(0, DREP_A, 200, 84974395L);
        registerDRep(0, DREP_B, 181, 76909405L);

        storeDRepDelegation(0, CRED1, 0, DREP_A, 85000000L);
        storeDRepDelegation(0, CRED2, 0, DREP_B, 85000001L);

        storeStakeAccount(0, CRED1, BigInteger.ZERO, BigInteger.valueOf(2_000_000));
        storeStakeAccount(0, CRED2, BigInteger.ZERO, BigInteger.valueOf(2_000_000));

        var utxoBalances = Map.of(
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED1), BigInteger.valueOf(100_000_000),
                new com.bloxbean.cardano.yaci.node.ledgerstate.UtxoBalanceAggregator.CredentialKey(0, CRED2), BigInteger.valueOf(200_000_000));

        var dist = calculator.calculate(230, utxoBalances, Map.of());

        assertThat(dist.get(new DRepDistributionCalculator.DRepDistKey(0, DREP_A))).isEqualTo(BigInteger.valueOf(100_000_000));
        assertThat(dist.get(new DRepDistributionCalculator.DRepDistKey(0, DREP_B))).isEqualTo(BigInteger.valueOf(200_000_000));
    }
}
