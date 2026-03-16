package com.bloxbean.cardano.yaci.node.app.api.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.yaci.node.app.e2e.BaseE2ETest;
import com.bloxbean.cardano.yaci.node.app.e2e.DevnetTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DevnetTestProfile.class)
@Tag("integration")
public class TxBatchSubmissionIT extends BaseE2ETest {

    private static final int TX_COUNT = 50;

    private Account sender;

    @Override
    protected int getAccountBaseIndex() {
        return 80;
    }

    @BeforeAll
    void fundSender() throws Exception {
        sender = getAccount(0);
        fundAddress(sender.enterpriseAddress(), 10000);
    }

    @Test
    void submitBatchSelfTransfers() throws Exception {
        String senderAddr = sender.enterpriseAddress();

        List<String> submittedHashes = new ArrayList<>();
        int failures = 0;

        for (int i = 0; i < TX_COUNT; i++) {
            try {
                QuickTxBuilder txBuilder = new QuickTxBuilder(backendService);
                Tx tx = new Tx()
                        .payToAddress(senderAddr, Amount.ada(1))
                        .from(senderAddr);

                Result<String> result = txBuilder.compose(tx)
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
