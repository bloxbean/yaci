package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
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
 * E2E tests for devnet-specific APIs: fund, snapshot/restore, time advance, rollback.
 */
@io.quarkus.test.junit.QuarkusTest
@io.quarkus.test.junit.TestProfile(DevnetTestProfile.class)
class DevnetApiE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(DevnetApiE2ETest.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private Account account;

    @Override
    protected int getAccountBaseIndex() {
        return 60;
    }

    @BeforeAll
    void setup() {
        account = getAccount(0);
    }

    @Test
    @Order(1)
    void fundAddressAndVerify() throws Exception {
        String address = account.enterpriseAddress();
        JsonNode fundResult = postJson("devnet/fund", """
                {"address":"%s","ada":5000}
                """.formatted(address));

        assertNotNull(fundResult.get("tx_hash"), "Fund should return tx_hash");
        assertTrue(fundResult.get("lovelace").asLong() > 0, "Should return lovelace amount");

        String txHash = fundResult.get("tx_hash").asText();
        checkIfUtxoAvailable(txHash, address);

        List<Utxo> utxos = utxoSupplier.getAll(address);
        assertFalse(utxos.isEmpty(), "Account should have UTXOs after funding");
        log.info("Funded and verified: txHash={}, lovelace={}", txHash, fundResult.get("lovelace"));
    }

    @Test
    @Order(2)
    void createAndListAndDeleteSnapshot() throws Exception {
        // Create snapshot
        JsonNode createResult = postJson("devnet/snapshot", """
                {"name":"e2e-test-snap"}
                """);
        assertEquals("e2e-test-snap", createResult.get("name").asText());
        assertTrue(createResult.get("slot").asLong() >= 0);
        log.info("Snapshot created: slot={}, block={}", createResult.get("slot"), createResult.get("block_number"));

        // List snapshots
        JsonNode snapshots = getJson("devnet/snapshots");
        assertTrue(snapshots.isArray(), "Should return array");
        boolean found = false;
        for (JsonNode snap : snapshots) {
            if ("e2e-test-snap".equals(snap.get("name").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Should find our snapshot in the list");
        log.info("Snapshot found in list (total: {})", snapshots.size());

        // Delete snapshot
        deleteJson("devnet/snapshot/e2e-test-snap");
        log.info("Snapshot deleted successfully");
    }

    @Test
    @Order(3)
    void snapshotAndRestore() throws Exception {
        // Get current tip
        JsonNode blockBefore = getJson("blocks/latest");
        long heightBefore = blockBefore.get("height").asLong();

        // Create snapshot
        postJson("devnet/snapshot", """
                {"name":"e2e-restore-test"}
                """);

        // Wait for a few more blocks
        Thread.sleep(1000);

        JsonNode blockAfter = getJson("blocks/latest");
        long heightAfter = blockAfter.get("height").asLong();
        assertTrue(heightAfter > heightBefore, "Chain should have progressed");

        // Restore snapshot
        JsonNode restoreResult = postJson("devnet/restore/e2e-restore-test", "");
        assertNotNull(restoreResult.get("message"));
        log.info("Restored snapshot: {}", restoreResult.get("message").asText());

        // Clean up
        deleteJson("devnet/snapshot/e2e-restore-test");
    }

    @Test
    @Order(4)
    void timeAdvanceBySlots() throws Exception {
        JsonNode blockBefore = getJson("blocks/latest");
        long slotBefore = blockBefore.get("slot").asLong();

        JsonNode result = postJson("devnet/time/advance", """
                {"slots":10}
                """);

        assertNotNull(result.get("new_slot"));
        assertTrue(result.get("new_slot").asLong() > slotBefore, "Slot should advance");
        assertTrue(result.get("blocks_produced").asInt() > 0, "Should produce blocks");
        log.info("Time advanced: new_slot={}, blocks_produced={}",
                result.get("new_slot"), result.get("blocks_produced"));
    }

    @Test
    @Order(5)
    void rollbackByCount() throws Exception {
        // Get current tip
        JsonNode blockBefore = getJson("blocks/latest");
        long heightBefore = blockBefore.get("height").asLong();
        assertTrue(heightBefore > 5, "Need enough blocks to rollback");

        JsonNode result = postJson("devnet/rollback", """
                {"count":3}
                """);

        assertNotNull(result.get("slot"));
        long newBlock = result.get("block_number").asLong();
        assertTrue(newBlock < heightBefore, "Block height should decrease after rollback");
        log.info("Rolled back: before={}, after={}", heightBefore, newBlock);
    }

    @Test
    @Order(6)
    void genesisDownload() throws Exception {
        String url = baseUrl + "devnet/genesis/download";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), "Genesis download should succeed");
        assertTrue(response.body().length > 100, "ZIP should have content");

        // Verify it's a valid ZIP (starts with PK\x03\x04)
        assertEquals(0x50, response.body()[0] & 0xFF);
        assertEquals(0x4B, response.body()[1] & 0xFF);
        log.info("Genesis ZIP downloaded: {} bytes", response.body().length);
    }

    // --- Helpers ---

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

    private JsonNode postJson(String path, String body) throws Exception {
        String url = baseUrl + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json");

        if (body == null || body.isBlank()) {
            builder.POST(HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST " + path + " failed: " + response.body());
        return mapper.readTree(response.body());
    }

    private void deleteJson(String path) throws Exception {
        String url = baseUrl + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "DELETE " + path + " failed: " + response.body());
    }
}
