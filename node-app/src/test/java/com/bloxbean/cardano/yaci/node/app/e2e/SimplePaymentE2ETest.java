package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.cip.cip20.MessageMetadata;
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

@QuarkusTest
@TestProfile(DevnetTestProfile.class)
class SimplePaymentE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(SimplePaymentE2ETest.class);

    private Account account1;
    private Account account2;

    @Override
    protected int getAccountBaseIndex() {
        return 0;
    }

    @BeforeAll
    void fundAccounts() throws Exception {
        account1 = getAccount(0);
        account2 = getAccount(1);

        fundAddress(account1.enterpriseAddress(), 10000);
        fundAddress(account2.enterpriseAddress(), 10000);
    }

    @Test
    @Order(1)
    void simpleAdaTransfer() throws Exception {
        Tx tx = new Tx()
                .payToAddress(account2.enterpriseAddress(), Amount.ada(100))
                .from(account1.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account1))
                .complete();

        assertTrue(result.isSuccessful(), "Tx failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), account2.enterpriseAddress());
        log.info("Simple ADA transfer successful: {}", result.getValue());
    }

    @Test
    @Order(2)
    void multiOutputTransfer() throws Exception {
        Account account3 = getAccount(2);
        Account account4 = getAccount(3);

        Tx tx = new Tx()
                .payToAddress(account3.enterpriseAddress(), Amount.ada(50))
                .payToAddress(account4.enterpriseAddress(), Amount.ada(75))
                .from(account1.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account1))
                .complete();

        assertTrue(result.isSuccessful(), "Tx failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), account3.enterpriseAddress());
        checkIfUtxoAvailable(result.getValue(), account4.enterpriseAddress());
        log.info("Multi-output transfer successful: {}", result.getValue());
    }

    @Test
    @Order(3)
    void transferWithMetadata() throws Exception {
        MessageMetadata metadata = MessageMetadata.create()
                .add("Yaci E2E Test - CIP-20 message");

        Tx tx = new Tx()
                .payToAddress(account2.enterpriseAddress(), Amount.ada(10))
                .attachMetadata(metadata)
                .from(account1.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account1))
                .complete();

        assertTrue(result.isSuccessful(), "Tx failed: " + result.getResponse());
        waitForTransaction(result);
        log.info("Transfer with metadata successful: {}", result.getValue());
    }
}
