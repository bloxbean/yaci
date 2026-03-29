package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: validates DRep registration/deregistration via CCL supplementary rules.
 *
 * <p>Phase 2: Scalus has NO validators for GOVCERT certs (RegDRepCert, UnregDRepCert).
 * The CCL CertificateValidationRule now validates these via YaciDRepsSlice backed by
 * the account state store which tracks DRep registrations.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Register a DRep — should succeed</li>
 *   <li>Attempt to register the same DRep again — should be rejected (by CCL)</li>
 *   <li>Deregister the DRep — should succeed</li>
 *   <li>Register again after deregistration — should succeed</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(DevnetTestProfile.class)
class DRepRegistrationE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(DRepRegistrationE2ETest.class);

    private Account account;
    private String enterpriseAddress;
    private Credential drepCredential;

    @Override
    protected int getAccountBaseIndex() {
        return 400; // unique index to avoid collisions with other E2E tests
    }

    @BeforeAll
    void fundAccounts() throws Exception {
        account = getAccount(0);
        enterpriseAddress = account.enterpriseAddress();

        // DRep credential: use the stake key hash
        byte[] stakeVkBytes = account.stakeHdKeyPair().getPublicKey().getKeyData();
        byte[] stakeKeyHash = Blake2bUtil.blake2bHash224(stakeVkBytes);
        drepCredential = Credential.fromKey(stakeKeyHash);

        log.info("Enterprise address: {}", enterpriseAddress);
        log.info("DRep credential hash: {}", HexUtil.encodeHexString(stakeKeyHash));

        fundAddress(enterpriseAddress, 10000);
    }

    @Test
    @Order(1)
    void drepRegistration_shouldSucceed() throws Exception {
        Tx tx = new Tx()
                .registerDRep(drepCredential)
                .from(enterpriseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "DRep registration should succeed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), enterpriseAddress);
        log.info("DRep registration successful: {}", result.getValue());
    }

    @Test
    @Order(2)
    void doubleDRepRegistration_shouldFail() throws Exception {
        Tx tx = new Tx()
                .registerDRep(drepCredential)
                .from(enterpriseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertFalse(result.isSuccessful(),
                "Double DRep registration should be rejected but succeeded: " + result.getValue());
        log.info("Double DRep registration correctly rejected: {}", result.getResponse());
    }

    @Test
    @Order(3)
    void drepDeregistration_shouldSucceed() throws Exception {
        Tx tx = new Tx()
                .unregisterDRep(drepCredential)
                .from(enterpriseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "DRep deregistration should succeed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), enterpriseAddress);
        log.info("DRep deregistration successful: {}", result.getValue());
    }

    @Test
    @Order(4)
    void drepReRegistrationAfterDeregistration_shouldSucceed() throws Exception {
        Tx tx = new Tx()
                .registerDRep(drepCredential)
                .from(enterpriseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(),
                "DRep re-registration after deregistration should succeed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), enterpriseAddress);
        log.info("DRep re-registration successful: {}", result.getValue());
    }
}
