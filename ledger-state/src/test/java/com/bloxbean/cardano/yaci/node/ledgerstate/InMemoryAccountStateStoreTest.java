package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.node.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAccountStateStoreTest {

    private InMemoryAccountStateStore store;

    // Test credential: key hash type (0), arbitrary 28-byte hash
    private static final String CRED_HASH_1 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef01";
    private static final String CRED_HASH_2 = "11111111111111111111111111111111111111111111111111111111";
    private static final String POOL_HASH_1 = "deadbeef00000000000000000000000000000000000000000000cafe";
    private static final String POOL_HASH_2 = "cafebabe00000000000000000000000000000000000000000000dead";

    @BeforeEach
    void setUp() {
        store = new InMemoryAccountStateStore();
    }

    @Test
    void stakeRegistration_createsAccountWithZeroReward() {
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getRewardBalance(0, CRED_HASH_1)).contains(BigInteger.ZERO);
        assertThat(store.getStakeDeposit(0, CRED_HASH_1)).contains(BigInteger.ZERO);
    }

    @Test
    void regCert_createsAccountWithDeposit() {
        applyBlockWithCerts(1, 100,
                RegCert.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .coin(BigInteger.valueOf(2_000_000))
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getStakeDeposit(0, CRED_HASH_1)).contains(BigInteger.valueOf(2_000_000));
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(2_000_000));
    }

    @Test
    void stakeDeregistration_removesAccount() {
        // Register first
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();

        // Deregister
        applyBlockWithCerts(2, 200,
                StakeDeregistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isFalse();
        assertThat(store.getRewardBalance(0, CRED_HASH_1)).isEmpty();
    }

    @Test
    void stakeDelegation_recordsPoolDelegation() {
        // Register + delegate
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build(),
                StakeDelegation.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .stakePoolId(StakePoolId.builder().poolKeyHash(POOL_HASH_1).build())
                        .build());

        assertThat(store.getDelegatedPool(0, CRED_HASH_1)).contains(POOL_HASH_1);
    }

    @Test
    void voteDelegCert_recordsDRepDelegation() {
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build(),
                VoteDelegCert.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .drep(Drep.abstain())
                        .build());

        var deleg = store.getDRepDelegation(0, CRED_HASH_1);
        assertThat(deleg).isPresent();
        assertThat(deleg.get().drepType()).isEqualTo(2); // ABSTAIN
    }

    @Test
    void poolRegistration_recordsPool() {
        applyBlockWithCerts(1, 100,
                PoolRegistration.builder()
                        .poolParams(PoolParams.builder()
                                .operator(POOL_HASH_1)
                                .pledge(BigInteger.valueOf(500_000_000))
                                .build())
                        .build());

        assertThat(store.isPoolRegistered(POOL_HASH_1)).isTrue();
        // Pool deposit is zero until protocol params are threaded in (Phase 2)
        assertThat(store.getPoolDeposit(POOL_HASH_1)).contains(BigInteger.ZERO);
    }

    @Test
    void poolRetirement_recordsEpoch() {
        // Register pool first
        applyBlockWithCerts(1, 100,
                PoolRegistration.builder()
                        .poolParams(PoolParams.builder()
                                .operator(POOL_HASH_1)
                                .pledge(BigInteger.valueOf(500_000_000))
                                .build())
                        .build());

        // Retire pool
        applyBlockWithCerts(2, 200,
                PoolRetirement.builder()
                        .poolKeyHash(POOL_HASH_1)
                        .epoch(42)
                        .build());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH_1)).contains(42L);
    }

    @Test
    void poolRegistration_cancelsRetirement() {
        // Register, retire, re-register
        applyBlockWithCerts(1, 100,
                PoolRegistration.builder()
                        .poolParams(PoolParams.builder()
                                .operator(POOL_HASH_1)
                                .pledge(BigInteger.valueOf(500_000_000))
                                .build())
                        .build());

        applyBlockWithCerts(2, 200,
                PoolRetirement.builder()
                        .poolKeyHash(POOL_HASH_1)
                        .epoch(42)
                        .build());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH_1)).isPresent();

        applyBlockWithCerts(3, 300,
                PoolRegistration.builder()
                        .poolParams(PoolParams.builder()
                                .operator(POOL_HASH_1)
                                .pledge(BigInteger.valueOf(600_000_000))
                                .build())
                        .build());

        assertThat(store.getPoolRetirementEpoch(POOL_HASH_1)).isEmpty();
        assertThat(store.getPoolDeposit(POOL_HASH_1)).contains(BigInteger.ZERO);
    }

    @Test
    void rollback_restoresState() {
        // Block 1: register
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        // Block 2: delegate
        applyBlockWithCerts(2, 200,
                StakeDelegation.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .stakePoolId(StakePoolId.builder().poolKeyHash(POOL_HASH_1).build())
                        .build());

        // Block 3: register second cred
        applyBlockWithCerts(3, 300,
                RegCert.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_2)
                                .build())
                        .coin(BigInteger.valueOf(2_000_000))
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_2)).isTrue();
        assertThat(store.getDelegatedPool(0, CRED_HASH_1)).contains(POOL_HASH_1);

        // Rollback to slot 200 (should undo block 3 only)
        store.rollbackTo(new RollbackEvent(new Point(200, "hash"), true));

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getDelegatedPool(0, CRED_HASH_1)).contains(POOL_HASH_1);
        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_2)).isFalse();
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.ZERO);

        // Rollback further to slot 100 (should undo block 2)
        store.rollbackTo(new RollbackEvent(new Point(100, "hash"), true));

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getDelegatedPool(0, CRED_HASH_1)).isEmpty();
    }

    @Test
    void scriptCredType_treatedDistinctly() {
        // Register same hash with key type
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        // Register same hash with script type
        applyBlockWithCerts(2, 200,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.SCRIPTHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        // Both should be registered independently
        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.isStakeCredentialRegistered(1, CRED_HASH_1)).isTrue();
    }

    @Test
    void withdrawal_debitsRewardBalance() {
        // Register stake credential
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        // Apply block with withdrawal (reward addr = e0 + credential hash for key hash on testnet)
        String rewardAddrHex = "e0" + CRED_HASH_1;
        Map<String, BigInteger> withdrawals = new LinkedHashMap<>();
        withdrawals.put(rewardAddrHex, BigInteger.ZERO);

        applyBlockWithWithdrawals(2, 200, withdrawals);

        // Reward balance should still be 0 (was 0, debited 0)
        assertThat(store.getRewardBalance(0, CRED_HASH_1)).contains(BigInteger.ZERO);
    }

    @Test
    void stakeVoteRegDelegCert_registersAndDelegatesBoth() {
        applyBlockWithCerts(1, 100,
                StakeVoteRegDelegCert.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .poolKeyHash(POOL_HASH_1)
                        .drep(Drep.noConfidence())
                        .coin(BigInteger.valueOf(2_000_000))
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getStakeDeposit(0, CRED_HASH_1)).contains(BigInteger.valueOf(2_000_000));
        assertThat(store.getDelegatedPool(0, CRED_HASH_1)).contains(POOL_HASH_1);
        var drep = store.getDRepDelegation(0, CRED_HASH_1);
        assertThat(drep).isPresent();
        assertThat(drep.get().drepType()).isEqualTo(3); // NO_CONFIDENCE
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(2_000_000));
    }

    @Test
    void notEnabled_noOps() {
        store = new InMemoryAccountStateStore(false);
        assertThat(store.isEnabled()).isFalse();

        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isFalse();
    }

    @Test
    void shelleyRegistration_usesKeyDeposit() {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
        };
        store = new InMemoryAccountStateStore(true, provider);

        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getStakeDeposit(0, CRED_HASH_1)).contains(BigInteger.valueOf(2_000_000));
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(2_000_000));
    }

    @Test
    void shelleyDeregistration_refundsKeyDeposit() {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
        };
        store = new InMemoryAccountStateStore(true, provider);

        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(2_000_000));

        applyBlockWithCerts(2, 200,
                StakeDeregistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isFalse();
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void shelleyReRegistration_afterDeregistration() {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
        };
        store = new InMemoryAccountStateStore(true, provider);

        // Register
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(2_000_000));

        // Deregister
        applyBlockWithCerts(2, 200,
                StakeDeregistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());
        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isFalse();

        // Re-register
        applyBlockWithCerts(3, 300,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isTrue();
        assertThat(store.getStakeDeposit(0, CRED_HASH_1)).contains(BigInteger.valueOf(2_000_000));
        assertThat(store.getTotalDeposited()).isEqualTo(BigInteger.valueOf(2_000_000));
    }

    @Test
    void deregistration_clearsDRepDelegation() {
        store = new InMemoryAccountStateStore(true);

        // Register + DRep delegation
        applyBlockWithCerts(1, 100,
                StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build(),
                VoteDelegCert.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .drep(Drep.abstain())
                        .build());

        assertThat(store.getDRepDelegation(0, CRED_HASH_1)).isPresent();

        // Deregister — removes entire account entry including DRep delegation
        // (per Haskell ledger: Map.extract removes entry, re-registration starts fresh)
        applyBlockWithCerts(2, 200,
                StakeDeregistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH)
                                .hash(CRED_HASH_1)
                                .build())
                        .build());

        assertThat(store.isStakeCredentialRegistered(0, CRED_HASH_1)).isFalse();
        assertThat(store.getDRepDelegation(0, CRED_HASH_1)).isEmpty();
    }

    // --- Helpers ---

    private void applyBlockWithCerts(long blockNo, long slot, Certificate... certs) {
        List<TransactionBody> txs = new ArrayList<>();
        TransactionBody tx = TransactionBody.builder()
                .certificates(new ArrayList<>(Arrays.asList(certs)))
                .build();
        txs.add(tx);

        Block block = Block.builder()
                .transactionBodies(txs)
                .build();

        store.applyBlock(new BlockAppliedEvent(Era.Conway, slot, blockNo, "hash" + blockNo, block));
    }

    private void applyBlockWithWithdrawals(long blockNo, long slot, Map<String, BigInteger> withdrawals) {
        List<TransactionBody> txs = new ArrayList<>();
        TransactionBody tx = TransactionBody.builder()
                .withdrawals(withdrawals)
                .build();
        txs.add(tx);

        Block block = Block.builder()
                .transactionBodies(txs)
                .build();

        store.applyBlock(new BlockAppliedEvent(Era.Conway, slot, blockNo, "hash" + blockNo, block));
    }
}
