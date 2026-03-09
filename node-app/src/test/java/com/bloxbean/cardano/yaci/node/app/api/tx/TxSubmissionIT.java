package com.bloxbean.cardano.yaci.node.app.api.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that builds, signs, and submits a transaction
 * using cardano-client-lib's QuickTxBuilder against a running yaci devnet node.
 *
 * <p>Requires a running devnet: {@code cd node-app && ./start-devnet.sh}
 *
 * <p>Run with: {@code ./gradlew :node-app:test --tests "TxSubmissionIT"}
 */
@Tag("integration")
public class TxSubmissionIT {

    private static final String MNEMONIC =
            "wrist approve ethics forest knife treat noise great three simple prize happy "
            + "toe dynamic number hunt trigger install wrong change decorate vendor glow erosion";

    @Test
    void submitSelfTransfer() throws Exception {
        BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "Dummy");

        Account sender = new Account(Networks.testnet(), MNEMONIC);
        String senderAddr = sender.enterpriseAddress();

        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backend);
        Tx tx = new Tx()
                .payToAddress(senderAddr, Amount.ada(10))
                .from(senderAddr);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(sender))
                .complete();

        assertTrue(result.isSuccessful(), "Tx submission failed: " + result.getResponse());
        assertNotNull(result.getValue(), "Tx hash should not be null");
        assertFalse(result.getValue().isBlank(), "Tx hash should not be blank");

        System.out.println("Submitted tx: " + result.getValue());
    }
}
