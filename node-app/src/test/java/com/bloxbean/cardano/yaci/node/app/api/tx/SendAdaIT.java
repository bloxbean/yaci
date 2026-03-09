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

@Tag("integration")
public class SendAdaIT {

    private static final String MNEMONIC =
            "wrist approve ethics forest knife treat noise great three simple prize happy "
            + "toe dynamic number hunt trigger install wrong change decorate vendor glow erosion";

    @Test
    void sendAda() throws Exception {
        BackendService backend = new BFBackendService("http://localhost:8080/api/v1/", "Dummy");
        Account sender = new Account(Networks.testnet(), MNEMONIC);
        String senderAddr = sender.enterpriseAddress();
        String receiver = "addr_test1qp73ljurtknpm5fgey5r2y9aympd33ksgw0f8rc5khheg83y35rncur9mjvs665cg4052985ry9rzzmqend9sqw0cdksxvefah";

        QuickTxBuilder quickTxBuilder = new QuickTxBuilder(backend);
        Tx tx = new Tx()
                .payToAddress(receiver, Amount.ada(1000))
                .from(senderAddr);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(sender))
                .complete();

        assertTrue(result.isSuccessful(), "Tx failed: " + result.getResponse());
        System.out.println("Sent 1000 ADA to " + receiver);
        System.out.println("Tx hash: " + result.getValue());
    }
}
