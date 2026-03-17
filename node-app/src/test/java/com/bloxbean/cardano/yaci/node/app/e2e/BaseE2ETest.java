package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(BaseE2ETest.class);

    protected static final String MNEMONIC =
            "wrist approve ethics forest knife treat noise great three simple prize happy "
            + "toe dynamic number hunt trigger install wrong change decorate vendor glow erosion";

    @TestHTTPResource
    URL quarkusTestUrl;

    protected BackendService backendService;
    protected QuickTxBuilder quickTxBuilder;
    protected UtxoSupplier utxoSupplier;
    protected String baseUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    protected abstract int getAccountBaseIndex();

    protected Account getAccount(int offset) {
        return new Account(Networks.testnet(), MNEMONIC, getAccountBaseIndex() + offset);
    }

    @BeforeAll
    void setUp() {
        String externalUrl = System.getProperty("yaci.e2e.baseUrl");
        if (externalUrl != null && !externalUrl.isBlank()) {
            baseUrl = externalUrl;
        } else {
            baseUrl = quarkusTestUrl.toString() + "/api/v1/";
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }

        backendService = new BFBackendService(baseUrl, "Dummy");
        quickTxBuilder = new QuickTxBuilder(backendService);
        utxoSupplier = new DefaultUtxoSupplier(backendService.getUtxoService());

        log.info("E2E tests using base URL: {}", baseUrl);
    }

    protected void fundAddress(String address, long adaAmount) throws Exception {
        String fundUrl = baseUrl + "devnet/fund";
        String body = objectMapper.writeValueAsString(
                new FundPayload(address, adaAmount)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fundUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Fund request failed (" + response.statusCode() + "): " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String txHash = json.get("tx_hash").asText();
        log.info("Funded {} with {} ADA, txHash: {}", address, adaAmount, txHash);

        // Wait until the funded UTXO is visible
        checkIfUtxoAvailable(txHash, address);
    }

    protected void waitForTransaction(Result<String> result) throws Exception {
        if (!result.isSuccessful()) {
            throw new RuntimeException("Transaction failed: " + result.getResponse());
        }

        String txHash = result.getValue();
        log.info("Waiting for transaction: {}", txHash);

        for (int i = 0; i < 60; i++) {
            try {
                var txResult = backendService.getTransactionService().getTransaction(txHash);
                if (txResult.isSuccessful()) {
                    log.info("Transaction confirmed: {}", txHash);
                    return;
                }
            } catch (Exception e) {
                // ignore and retry
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Transaction not confirmed after 30s: " + txHash);
    }

    protected void checkIfUtxoAvailable(String txHash, String address) throws Exception {
        log.info("Waiting for UTXO from tx {} at {}", txHash, address);

        for (int i = 0; i < 30; i++) {
            try {
                List<Utxo> utxos = utxoSupplier.getAll(address);
                boolean found = utxos.stream()
                        .anyMatch(u -> u.getTxHash().equals(txHash));
                if (found) {
                    log.info("UTXO found for tx {} at {}", txHash, address);
                    return;
                }
            } catch (Exception e) {
                // ignore and retry
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("UTXO not available after 30s for tx " + txHash + " at " + address);
    }

    private record FundPayload(String address, long ada) {}
}
