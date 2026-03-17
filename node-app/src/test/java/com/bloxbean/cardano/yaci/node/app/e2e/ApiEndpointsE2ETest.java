package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for REST API endpoints: blocks, UTXOs, transactions, epochs, protocol params.
 */
@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(DevnetTestProfile.class)
class ApiEndpointsE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(ApiEndpointsE2ETest.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private Account account1;
    private Account account2;
    private String txHash;

    @Override
    protected int getAccountBaseIndex() {
        return 40;
    }

    @BeforeAll
    void fundAndTransfer() throws Exception {
        account1 = getAccount(0);
        account2 = getAccount(1);

        fundAddress(account1.enterpriseAddress(), 10000);

        // Make a tx so we have something to query
        Tx tx = new Tx()
                .payToAddress(account2.enterpriseAddress(), Amount.ada(500))
                .from(account1.enterpriseAddress());

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account1))
                .complete();
        assertTrue(result.isSuccessful(), "Setup tx failed: " + result.getResponse());
        waitForTransaction(result);
        txHash = result.getValue();
        checkIfUtxoAvailable(txHash, account2.enterpriseAddress());
    }

    // --- Block API ---

    @Test
    @Order(1)
    void getLatestBlock() throws Exception {
        JsonNode block = getJson("blocks/latest");
        assertNotNull(block.get("hash"), "Block hash should be present");
        assertTrue(block.get("height").asLong() > 0, "Block height should be > 0");
        assertTrue(block.get("slot").asLong() > 0, "Block slot should be > 0");
        assertNotNull(block.get("epoch"), "Epoch should be present");
        log.info("Latest block: height={}, slot={}", block.get("height"), block.get("slot"));
    }

    @Test
    @Order(2)
    void getBlockByNumber() throws Exception {
        JsonNode latest = getJson("blocks/latest");
        long height = latest.get("height").asLong();

        JsonNode block = getJson("blocks/" + height);
        assertEquals(height, block.get("height").asLong());
        assertNotNull(block.get("hash").asText());
        log.info("Block #{}: hash={}", height, block.get("hash").asText());
    }

    @Test
    @Order(3)
    void getBlockByHash() throws Exception {
        JsonNode latest = getJson("blocks/latest");
        String hash = latest.get("hash").asText();

        JsonNode block = getJson("blocks/" + hash);
        assertEquals(hash, block.get("hash").asText());
        log.info("Block by hash: height={}", block.get("height"));
    }

    // --- UTXO API ---

    @Test
    @Order(10)
    void getUtxosByAddress() throws Exception {
        JsonNode utxos = getJson("addresses/" + account2.enterpriseAddress() + "/utxos");
        assertTrue(utxos.isArray(), "Should return array");
        assertTrue(utxos.size() > 0, "Should have at least 1 UTXO");

        JsonNode first = utxos.get(0);
        assertNotNull(first.get("tx_hash"), "UTXO should have tx_hash");
        assertNotNull(first.get("address"), "UTXO should have address");
        assertNotNull(first.get("amount"), "UTXO should have amount");
        log.info("Found {} UTXOs for account2", utxos.size());
    }

    @Test
    @Order(11)
    void getUtxosByAddressFilterLovelace() throws Exception {
        JsonNode utxos = getJson("addresses/" + account2.enterpriseAddress() + "/utxos/lovelace");
        assertTrue(utxos.isArray(), "Should return array");
        assertTrue(utxos.size() > 0, "Should have at least 1 UTXO with lovelace");
        log.info("Found {} UTXOs with lovelace for account2", utxos.size());
    }

    @Test
    @Order(12)
    void getSpecificUtxo() throws Exception {
        // Get UTXOs for account2, then query a specific one
        JsonNode utxos = getJson("addresses/" + account2.enterpriseAddress() + "/utxos");
        assertTrue(utxos.size() > 0);

        String utxoTxHash = utxos.get(0).get("tx_hash").asText();
        int index = utxos.get(0).get("output_index").asInt();

        JsonNode utxo = getJson("utxos/" + utxoTxHash + "/" + index);
        assertEquals(utxoTxHash, utxo.get("tx_hash").asText());
        assertEquals(index, utxo.get("output_index").asInt());
        log.info("Specific UTXO: {}#{}", utxoTxHash, index);
    }

    @Test
    @Order(13)
    void getUtxosByAddressViaBackendService() throws Exception {
        // Test via cardano-client-lib's BackendService (the same path the quicktx builder uses)
        List<Utxo> utxos = utxoSupplier.getAll(account1.enterpriseAddress());
        assertFalse(utxos.isEmpty(), "Account1 should have UTXOs");

        Utxo first = utxos.get(0);
        assertNotNull(first.getTxHash());
        assertTrue(first.getAmount().stream()
                .anyMatch(a -> "lovelace".equals(a.getUnit())), "Should have lovelace");
        log.info("BackendService found {} UTXOs for account1", utxos.size());
    }

    // --- Transaction API ---

    @Test
    @Order(20)
    void getTransactionInfo() throws Exception {
        JsonNode txInfo = getJson("txs/" + txHash);
        assertEquals(txHash, txInfo.get("hash").asText());
        assertTrue(txInfo.get("block_height").asLong() > 0);
        assertNotNull(txInfo.get("fees"));
        assertTrue(txInfo.get("valid_contract").asBoolean());
        log.info("Tx info: hash={}, block={}, fees={}", txHash, txInfo.get("block_height"), txInfo.get("fees"));
    }

    @Test
    @Order(21)
    void getTransactionUtxos() throws Exception {
        JsonNode txUtxos = getJson("txs/" + txHash + "/utxos");
        assertEquals(txHash, txUtxos.get("hash").asText());

        JsonNode inputs = txUtxos.get("inputs");
        JsonNode outputs = txUtxos.get("outputs");
        assertNotNull(inputs, "Should have inputs");
        assertNotNull(outputs, "Should have outputs");
        assertTrue(inputs.isArray() && inputs.size() > 0, "Should have at least 1 input");
        assertTrue(outputs.isArray() && outputs.size() > 0, "Should have at least 1 output");
        log.info("Tx UTXOs: {} inputs, {} outputs", inputs.size(), outputs.size());
    }

    // --- Epoch & Protocol Params API ---

    @Test
    @Order(30)
    void getLatestEpoch() throws Exception {
        JsonNode epoch = getJson("epochs/latest");
        assertNotNull(epoch.get("epoch"), "Should have epoch number");
        log.info("Current epoch: {}", epoch.get("epoch"));
    }

    @Test
    @Order(31)
    void getLatestProtocolParameters() throws Exception {
        JsonNode params = getJson("epochs/latest/parameters");
        assertNotNull(params.get("epoch"), "Should have epoch");
        assertNotNull(params.get("min_fee_a"), "Should have min_fee_a");
        assertNotNull(params.get("min_fee_b"), "Should have min_fee_b");
        assertNotNull(params.get("max_tx_size"), "Should have max_tx_size");
        log.info("Protocol params: epoch={}, min_fee_a={}, min_fee_b={}",
                params.get("epoch"), params.get("min_fee_a"), params.get("min_fee_b"));
    }

    @Test
    @Order(32)
    void getProtocolParametersByEpoch() throws Exception {
        JsonNode params = getJson("epochs/0/parameters");
        assertNotNull(params.get("epoch"), "Should have epoch");
        assertEquals(0, params.get("epoch").asInt());
        log.info("Epoch 0 protocol params retrieved successfully");
    }

    // --- Genesis API ---

    @Test
    @Order(40)
    void getGenesisParameters() throws Exception {
        JsonNode genesis = getJson("genesis");
        assertNotNull(genesis, "Should return genesis info");
        log.info("Genesis parameters retrieved successfully");
    }

    // --- Helper ---

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
