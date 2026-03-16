package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.ScriptUtxoFinders;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.address.AddressProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(DevnetTestProfile.class)
class AlwaysTrueScriptE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(AlwaysTrueScriptE2ETest.class);

    private static final String ALWAYS_TRUE_CBOR = "49480100002221200101";

    private Account account;
    private PlutusV2Script alwaysTrueScript;
    private String scriptAddress;
    private String lockTxHash;

    @Override
    protected int getAccountBaseIndex() {
        return 10;
    }

    @BeforeAll
    void fundAndSetup() throws Exception {
        account = getAccount(0);
        fundAddress(account.enterpriseAddress(), 10000);

        alwaysTrueScript = PlutusV2Script.builder()
                .cborHex(ALWAYS_TRUE_CBOR)
                .build();
        scriptAddress = AddressProvider.getEntAddress(alwaysTrueScript, Networks.testnet()).toBech32();
        log.info("Always-true script address: {}", scriptAddress);
    }

    @Test
    @Order(1)
    void lockFundsToScript() throws Exception {
        BigIntPlutusData datum = BigIntPlutusData.of(42);

        Tx tx = new Tx()
                .payToContract(scriptAddress, Amount.ada(4), datum)
                .from(account.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx failed: " + result.getResponse());
        waitForTransaction(result);
        lockTxHash = result.getValue();
        checkIfUtxoAvailable(lockTxHash, scriptAddress);
        log.info("Locked 4 ADA to script, txHash: {}", lockTxHash);
    }

    @Test
    @Order(2)
    void unlockFundsFromScript() throws Exception {
        assertNotNull(lockTxHash, "Lock tx must succeed first");

        BigIntPlutusData datum = BigIntPlutusData.of(42);
        BigIntPlutusData redeemer = BigIntPlutusData.of(1);

        Optional<Utxo> scriptUtxo = ScriptUtxoFinders.findFirstByInlineDatum(
                utxoSupplier, scriptAddress, datum);
        assertTrue(scriptUtxo.isPresent(), "Script UTXO not found");

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(scriptUtxo.get(), redeemer)
                .payToAddress(account.enterpriseAddress(), Amount.ada(3))
                .attachSpendingValidator(alwaysTrueScript)
                .withChangeAddress(account.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(scriptTx)
                .feePayer(account.enterpriseAddress())
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Unlock tx failed: " + result.getResponse());
        waitForTransaction(result);
        log.info("Unlocked funds from script, txHash: {}", result.getValue());
    }
}
