package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
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

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: full stake lifecycle — register, withdraw, deregister, verify withdrawal
 * fails after deregistration, then re-register and withdraw again.
 *
 * Validates that the LedgerStateProvider correctly populates CertState
 * so that Scalus CardanoMutator.transit() accepts/rejects withdrawal transactions
 * based on stake registration state.
 */
@QuarkusTest
@TestProfile(DevnetTestProfile.class)
class StakeRegistrationWithdrawalE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(StakeRegistrationWithdrawalE2ETest.class);

    private Account account;
    private String baseAddress;

    @Override
    protected int getAccountBaseIndex() {
        return 200; // unique index to avoid collisions with other E2E tests
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
    void stakeRegistration() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Stake registration failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Stake registration successful: {}", result.getValue());
    }

    @Test
    @Order(2)
    void withdrawalZero() throws Exception {
        Address stakeAddr = AddressProvider.getStakeAddress(new Address(baseAddress));

        Tx tx = new Tx()
                .withdraw(stakeAddr, BigInteger.ZERO)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Withdrawal 0 failed: " + result.getResponse());
        waitForTransaction(result);
        log.info("Withdrawal 0 successful: {}", result.getValue());
    }

    @Test
    @Order(3)
    void stakeDeregistration() throws Exception {
        Tx tx = new Tx()
                .deregisterStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Stake deregistration failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Stake deregistration successful: {}", result.getValue());
    }

    @Test
    @Order(4)
    void withdrawalAfterDeregistration_shouldFail() throws Exception {
        Address stakeAddr = AddressProvider.getStakeAddress(new Address(baseAddress));

        Tx tx = new Tx()
                .withdraw(stakeAddr, BigInteger.ZERO)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertFalse(result.isSuccessful(),
                "Withdrawal should fail after deregistration but succeeded: " + result.getValue());
        log.info("Withdrawal correctly rejected after deregistration: {}", result.getResponse());
    }

    @Test
    @Order(5)
    void stakeReRegistration() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Stake re-registration failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Stake re-registration successful: {}", result.getValue());
    }

    @Test
    @Order(6)
    void withdrawalAfterReRegistration() throws Exception {
        Address stakeAddr = AddressProvider.getStakeAddress(new Address(baseAddress));

        Tx tx = new Tx()
                .withdraw(stakeAddr, BigInteger.ZERO)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(),
                "Withdrawal after re-registration failed: " + result.getResponse());
        waitForTransaction(result);
        log.info("Withdrawal after re-registration successful: {}", result.getValue());
    }
}
