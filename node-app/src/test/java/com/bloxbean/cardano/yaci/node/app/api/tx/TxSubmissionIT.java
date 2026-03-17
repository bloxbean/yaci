package com.bloxbean.cardano.yaci.node.app.api.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.yaci.node.app.e2e.BaseE2ETest;
import com.bloxbean.cardano.yaci.node.app.e2e.DevnetTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DevnetTestProfile.class)
@Tag("integration")
public class TxSubmissionIT extends BaseE2ETest {

    private Account sender;

    @Override
    protected int getAccountBaseIndex() {
        return 70;
    }

    @BeforeAll
    void fundSender() throws Exception {
        sender = getAccount(0);
        fundAddress(sender.enterpriseAddress(), 10000);
    }

    @Test
    void submitSelfTransfer() throws Exception {
        String senderAddr = sender.enterpriseAddress();

        Tx tx = new Tx()
                .payToAddress(senderAddr, Amount.ada(10))
                .from(senderAddr);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(sender))
                .complete();

        assertTrue(result.isSuccessful(), "Tx submission failed: " + result.getResponse());
        assertNotNull(result.getValue(), "Tx hash should not be null");
        assertFalse(result.getValue().isBlank(), "Tx hash should not be blank");

        waitForTransaction(result);
    }
}
