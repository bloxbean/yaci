package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: validates that double stake registration is rejected by Scalus.
 *
 * <p>After removing the ad-hoc DELEG pre-validation from ScalusBasedTransactionValidator,
 * Scalus v0.16.0's StakeCertificatesValidator handles these checks with proper intra-tx
 * state tracking. This test verifies the behavior end-to-end.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Register a stake key — should succeed</li>
 *   <li>Attempt to register the same stake key again — should be rejected</li>
 *   <li>Deregister the stake key — should succeed</li>
 *   <li>Register again after deregistration — should succeed</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(DevnetTestProfile.class)
class DoubleStakeRegistrationE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(DoubleStakeRegistrationE2ETest.class);

    private Account account;
    private String baseAddress;

    @Override
    protected int getAccountBaseIndex() {
        return 300; // unique index to avoid collisions with other E2E tests
    }

    @BeforeAll
    void fundAccounts() throws Exception {
        account = getAccount(0);
        baseAddress = account.baseAddress();

        log.info("Base address: {}", baseAddress);
        log.info("Stake address: {}", account.stakeAddress());

        fundAddress(baseAddress, 10000);
    }

    @Test
    @Order(1)
    void stakeRegistration_shouldSucceed() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "First stake registration should succeed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("First stake registration successful: {}", result.getValue());
    }

    @Test
    @Order(2)
    void doubleStakeRegistration_shouldFail() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertFalse(result.isSuccessful(),
                "Double stake registration should be rejected but succeeded: " + result.getValue());
        log.info("Double stake registration correctly rejected: {}", result.getResponse());
    }

    @Test
    @Order(3)
    void stakeDeregistration_shouldSucceed() throws Exception {
        Tx tx = new Tx()
                .deregisterStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Stake deregistration should succeed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Stake deregistration successful: {}", result.getValue());
    }

    @Test
    @Order(4)
    void reRegistrationAfterDeregistration_shouldSucceed() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(),
                "Re-registration after deregistration should succeed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Re-registration after deregistration successful: {}", result.getValue());
    }
}
