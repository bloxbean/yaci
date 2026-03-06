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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch integration test: submit ~50 self-transfer transactions sequentially.
 * Each tx sends 1 ADA back to the sender address.
 *
 * <p>Requires a running devnet: {@code cd node-app && ./start-devnet.sh}
 *
 * <p>Run with: {@code ./gradlew :node-app:integrationTest --tests "TxBatchSubmissionIT"}
 */
@Tag("integration")
public class TxBatchSubmissionIT {

    private static final String MNEMONIC =
            "wrist approve ethics forest knife treat noise great three simple prize happy "
            + "toe dynamic number hunt trigger install wrong change decorate vendor glow erosion";

    private static final int TX_COUNT = 50;

    @Test
    void submitBatchSelfTransfers() throws Exception {
        BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "Dummy");
        Account sender = new Account(Networks.testnet(), MNEMONIC);
        String senderAddr = sender.enterpriseAddress();

        List<String> submittedHashes = new ArrayList<>();
        int failures = 0;

        for (int i = 0; i < TX_COUNT; i++) {
            try {
                QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backend);
                Tx tx = new Tx()
                        .payToAddress(senderAddr, Amount.ada(1))
                        .from(senderAddr);

                Result<String> result = quickTxBuilder.compose(tx)
                        .withSigner(SignerProviders.signerFrom(sender))
                        .complete();

                if (result.isSuccessful()) {
                    submittedHashes.add(result.getValue());
                    System.out.printf("[%d/%d] Submitted tx: %s%n", i + 1, TX_COUNT, result.getValue());
                } else {
                    failures++;
                    System.err.printf("[%d/%d] FAILED: %s%n", i + 1, TX_COUNT, result.getResponse());
                }

                // Small delay to let block producer include the tx and update UTXOs
                if ((i + 1) % 5 == 0) {
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                failures++;
                System.err.printf("[%d/%d] ERROR: %s%n", i + 1, TX_COUNT, e.getMessage());
            }
        }

        System.out.printf("%n=== Batch Result: %d submitted, %d failed ===%n", submittedHashes.size(), failures);
        submittedHashes.forEach(h -> System.out.println("  " + h));

        assertTrue(submittedHashes.size() >= TX_COUNT / 2,
                "At least half of the " + TX_COUNT + " txs should succeed, got " + submittedHashes.size());
    }
}
