package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.ScriptUtxoFinders;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
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
class ReferenceScriptV3E2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(ReferenceScriptV3E2ETest.class);

    // PlutusV3 sum script: validates datum + redeemer sum
    private static final String SUM_SCRIPT_CBOR = "46450101002499";

    private Account account1;
    private Account account2;
    private PlutusV3Script sumScript;
    private String scriptAddress;
    private String deployTxHash;

    @Override
    protected int getAccountBaseIndex() {
        return 20;
    }

    @BeforeAll
    void fundAndSetup() throws Exception {
        account1 = getAccount(0);
        account2 = getAccount(1);
        fundAddress(account1.enterpriseAddress(), 10000);

        sumScript = PlutusV3Script.builder()
                .cborHex(SUM_SCRIPT_CBOR)
                .build();
        scriptAddress = AddressProvider.getEntAddress(sumScript, Networks.testnet()).toBech32();
        log.info("PlutusV3 sum script address: {}", scriptAddress);
    }

    @Test
    @Order(1)
    void deployReferenceScriptAndLockFunds() throws Exception {
        BigIntPlutusData datum = BigIntPlutusData.of(8);

        // Single tx: deploy reference script to account2 + lock funds to script address
        Tx tx = new Tx()
                .payToAddress(account2.enterpriseAddress(), Amount.ada(1), sumScript)
                .payToContract(scriptAddress, Amount.ada(4), datum)
                .from(account1.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account1))
                .complete();

        assertTrue(result.isSuccessful(), "Deploy+lock tx failed: " + result.getResponse());
        waitForTransaction(result);
        deployTxHash = result.getValue();
        checkIfUtxoAvailable(deployTxHash, scriptAddress);
        checkIfUtxoAvailable(deployTxHash, account2.enterpriseAddress());
        log.info("Deployed ref script + locked funds, txHash: {}", deployTxHash);
    }

    @Test
    @Order(2)
    void spendWithReferenceScript() throws Exception {
        assertNotNull(deployTxHash, "Deploy tx must succeed first");

        BigIntPlutusData datum = BigIntPlutusData.of(8);
        BigIntPlutusData redeemer = BigIntPlutusData.of(36);

        // Find the script UTXO (locked funds)
        Optional<Utxo> scriptUtxo = ScriptUtxoFinders.findFirstByInlineDatum(
                utxoSupplier, scriptAddress, datum);
        assertTrue(scriptUtxo.isPresent(), "Script UTXO not found");

        // Find the reference script UTXO at account2
        Utxo refUtxo = utxoSupplier.getAll(account2.enterpriseAddress()).stream()
                .filter(u -> u.getTxHash().equals(deployTxHash) && u.getReferenceScriptHash() != null)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Reference script UTXO not found"));

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(scriptUtxo.get(), redeemer)
                .readFrom(refUtxo)
                .payToAddress(account1.enterpriseAddress(), Amount.ada(3))
                .withChangeAddress(account1.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(scriptTx)
                .feePayer(account1.enterpriseAddress())
                .withSigner(SignerProviders.signerFrom(account1))
                .complete();

        assertTrue(result.isSuccessful(), "Spend with ref script failed: " + result.getResponse());
        waitForTransaction(result);
        log.info("Spent with V3 reference script, txHash: {}", result.getValue());
    }
}
