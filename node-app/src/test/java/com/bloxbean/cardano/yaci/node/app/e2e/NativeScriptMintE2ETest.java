package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for native script token minting and querying minted assets.
 */
@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(DevnetTestProfile.class)
class NativeScriptMintE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(NativeScriptMintE2ETest.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private Account minter;
    private ScriptPubkey mintPolicy;
    private String policyId;
    private String mintTxHash;

    @Override
    protected int getAccountBaseIndex() {
        return 50;
    }

    @BeforeAll
    void fundAndSetup() throws Exception {
        minter = getAccount(0);
        fundAddress(minter.enterpriseAddress(), 10000);

        // Create a simple ScriptPubkey policy from the minter's verification key
        VerificationKey vkey = VerificationKey.create(minter.publicKeyBytes());
        mintPolicy = ScriptPubkey.create(vkey);
        policyId = mintPolicy.getPolicyId();
        log.info("Mint policy ID: {}", policyId);
    }

    @Test
    @Order(1)
    void mintTokens() throws Exception {
        Asset testToken = new Asset("TestToken", BigInteger.valueOf(1_000_000));
        Asset nftToken = new Asset("YaciNFT001", BigInteger.ONE);

        Tx tx = new Tx()
                .mintAssets(mintPolicy, List.of(testToken, nftToken), minter.enterpriseAddress())
                .from(minter.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(minter))
                .complete();

        assertTrue(result.isSuccessful(), "Mint tx failed: " + result.getResponse());
        waitForTransaction(result);
        mintTxHash = result.getValue();
        checkIfUtxoAvailable(mintTxHash, minter.enterpriseAddress());
        log.info("Minted tokens, txHash: {}", mintTxHash);
    }

    @Test
    @Order(2)
    void verifyMintedTokensInUtxos() throws Exception {
        assertNotNull(mintTxHash, "Mint tx must succeed first");

        List<Utxo> utxos = utxoSupplier.getAll(minter.enterpriseAddress());
        assertFalse(utxos.isEmpty(), "Minter should have UTXOs");

        // Check that at least one UTXO contains our minted token
        String testTokenUnit = policyId + HexUtil.encodeHexString("TestToken".getBytes());
        boolean hasTestToken = utxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .anyMatch(a -> testTokenUnit.equals(a.getUnit()));
        assertTrue(hasTestToken, "Should find TestToken in UTXOs");

        String nftUnit = policyId + HexUtil.encodeHexString("YaciNFT001".getBytes());
        boolean hasNft = utxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .anyMatch(a -> nftUnit.equals(a.getUnit()));
        assertTrue(hasNft, "Should find YaciNFT001 in UTXOs");

        log.info("Verified minted tokens present in UTXOs");
    }

    @Test
    @Order(3)
    void transferMintedToken() throws Exception {
        assertNotNull(mintTxHash, "Mint tx must succeed first");

        Account receiver = getAccount(1);
        String testTokenUnit = policyId + HexUtil.encodeHexString("TestToken".getBytes());

        Tx tx = new Tx()
                .payToAddress(receiver.enterpriseAddress(), List.of(
                        Amount.ada(2),
                        new Amount(testTokenUnit, BigInteger.valueOf(100))
                ))
                .from(minter.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(minter))
                .complete();

        assertTrue(result.isSuccessful(), "Transfer token tx failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), receiver.enterpriseAddress());

        // Verify receiver has the token
        List<Utxo> receiverUtxos = utxoSupplier.getAll(receiver.enterpriseAddress());
        boolean receiverHasToken = receiverUtxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .anyMatch(a -> testTokenUnit.equals(a.getUnit()));
        assertTrue(receiverHasToken, "Receiver should have TestToken");

        log.info("Transferred 100 TestToken to receiver, txHash: {}", result.getValue());
    }

    @Test
    @Order(4)
    void queryUtxosByAddressAndAsset() throws Exception {
        assertNotNull(mintTxHash, "Mint tx must succeed first");

        String testTokenUnit = policyId + HexUtil.encodeHexString("TestToken".getBytes());
        JsonNode utxos = getJson("addresses/" + minter.enterpriseAddress() + "/utxos/" + testTokenUnit);
        assertTrue(utxos.isArray(), "Should return array");
        assertTrue(utxos.size() > 0, "Should find UTXOs with TestToken");

        // Verify the returned UTXO contains the token
        boolean found = false;
        for (JsonNode utxo : utxos) {
            JsonNode amounts = utxo.get("amount");
            for (JsonNode amt : amounts) {
                if (testTokenUnit.equals(amt.get("unit").asText())) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "UTXO should contain TestToken amount");
        log.info("Found {} UTXOs with TestToken via API", utxos.size());
    }

    @Test
    @Order(5)
    void burnTokens() throws Exception {
        assertNotNull(mintTxHash, "Mint tx must succeed first");

        // Burn some tokens
        Asset burnToken = new Asset("TestToken", BigInteger.valueOf(-500));

        Tx tx = new Tx()
                .mintAssets(mintPolicy, burnToken)
                .from(minter.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(minter))
                .complete();

        assertTrue(result.isSuccessful(), "Burn tx failed: " + result.getResponse());
        waitForTransaction(result);
        log.info("Burned 500 TestToken, txHash: {}", result.getValue());
    }

    @Test
    @Order(6)
    void verifyTransactionUtxosAfterMint() throws Exception {
        assertNotNull(mintTxHash, "Mint tx must succeed first");

        JsonNode txUtxos = getJson("txs/" + mintTxHash + "/utxos");
        assertEquals(mintTxHash, txUtxos.get("hash").asText());

        JsonNode outputs = txUtxos.get("outputs");
        assertTrue(outputs.size() > 0, "Mint tx should have outputs");

        // Check that at least one output contains a multi-asset amount
        boolean hasMultiAsset = false;
        for (JsonNode output : outputs) {
            JsonNode amounts = output.get("amount");
            if (amounts.size() > 1) { // More than just lovelace
                hasMultiAsset = true;
                break;
            }
        }
        assertTrue(hasMultiAsset, "Mint tx output should contain multi-asset amounts");
        log.info("Mint tx UTXOs verified with multi-asset outputs");
    }

    private JsonNode getJson(String path) throws Exception {
        String url = baseUrl + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "GET " + path + " failed: " + response.body());
        return mapper.readTree(response.body());
    }
}
