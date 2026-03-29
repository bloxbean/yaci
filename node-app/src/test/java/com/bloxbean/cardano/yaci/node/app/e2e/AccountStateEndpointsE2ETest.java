package com.bloxbean.cardano.yaci.node.app.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test for /api/v1/accounts/* REST endpoints.
 * Submits stake registration, pool delegation, and DRep delegation transactions,
 * then verifies they appear via the listing endpoints.
 */
@QuarkusTest
@TestProfile(DevnetTestProfile.class)
class AccountStateEndpointsE2ETest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(AccountStateEndpointsE2ETest.class);

    // Devnet genesis pool hash from shelley-genesis.json
    private static final String DEVNET_POOL_HASH = "7301761068762f5900bde9eb7c1c15b09840285130f5b0f53606cc57";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private Account account;
    private String baseAddress;

    @Override
    protected int getAccountBaseIndex() {
        return 300; // unique index to avoid collisions with other E2E tests
    }

    @BeforeAll
    void fundAccounts() throws Exception {
        account = getAccount(0);
        baseAddress = account.baseAddress();
        log.info("Account base address: {}", baseAddress);
        log.info("Account stake address: {}", account.stakeAddress());
        fundAddress(baseAddress, 10000);
    }

    // --- Step 1: Register stake address ---

    @Test
    @Order(1)
    void stakeRegistration() throws Exception {
        Tx tx = new Tx()
                .registerStakeAddress(baseAddress)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Stake registration failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Stake registration tx: {}", result.getValue());
    }

    @Test
    @Order(2)
    void verifyRegistrationsEndpoint() throws Exception {
        JsonNode registrations = getJson("accounts/registrations");
        assertTrue(registrations.isArray(), "Should return array");
        assertTrue(registrations.size() > 0, "Should have at least 1 registration");

        JsonNode first = registrations.get(0);
        assertNotNull(first.get("credential"), "Should have credential");
        assertNotNull(first.get("credential_type"), "Should have credential_type");
        assertNotNull(first.get("reward_balance"), "Should have reward_balance");
        assertNotNull(first.get("deposit"), "Should have deposit");

        String credType = first.get("credential_type").asText();
        assertTrue("key".equals(credType) || "script".equals(credType),
                "credential_type should be 'key' or 'script', got: " + credType);

        log.info("Registrations: {} entries, first={}", registrations.size(), first);
    }

    // --- Step 2: Delegate to pool ---

    @Test
    @Order(3)
    void poolDelegation() throws Exception {
        Tx tx = new Tx()
                .delegateTo(baseAddress, DEVNET_POOL_HASH)
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Pool delegation failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("Pool delegation tx: {}", result.getValue());
    }

    @Test
    @Order(4)
    void verifyDelegationsEndpoint() throws Exception {
        JsonNode delegations = getJson("accounts/delegations");
        assertTrue(delegations.isArray(), "Should return array");
        assertTrue(delegations.size() > 0, "Should have at least 1 delegation");

        JsonNode first = delegations.get(0);
        assertNotNull(first.get("credential"), "Should have credential");
        assertNotNull(first.get("credential_type"), "Should have credential_type");
        assertNotNull(first.get("pool_hash"), "Should have pool_hash");
        assertNotNull(first.get("slot"), "Should have slot");
        assertNotNull(first.get("tx_index"), "Should have tx_index");
        assertNotNull(first.get("cert_index"), "Should have cert_index");

        // Verify the pool hash matches what we delegated to
        boolean found = false;
        for (int i = 0; i < delegations.size(); i++) {
            if (DEVNET_POOL_HASH.equals(delegations.get(i).get("pool_hash").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Should find delegation to devnet pool " + DEVNET_POOL_HASH);

        log.info("Delegations: {} entries", delegations.size());
    }

    // --- Step 3: DRep vote delegation (abstain) ---

    @Test
    @Order(5)
    void drepDelegation() throws Exception {
        Tx tx = new Tx()
                .delegateVotingPowerTo(baseAddress, DRep.abstain())
                .from(baseAddress);

        Result<String> result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(account))
                .withSigner(SignerProviders.stakeKeySignerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "DRep delegation failed: " + result.getResponse());
        waitForTransaction(result);
        checkIfUtxoAvailable(result.getValue(), baseAddress);
        log.info("DRep delegation tx: {}", result.getValue());
    }

    @Test
    @Order(6)
    void verifyDRepDelegationsEndpoint() throws Exception {
        JsonNode drepDelegations = getJson("accounts/drep-delegations");
        assertTrue(drepDelegations.isArray(), "Should return array");
        assertTrue(drepDelegations.size() > 0, "Should have at least 1 DRep delegation");

        JsonNode first = drepDelegations.get(0);
        assertNotNull(first.get("credential"), "Should have credential");
        assertNotNull(first.get("credential_type"), "Should have credential_type");
        assertNotNull(first.get("drep_type"), "Should have drep_type");
        assertNotNull(first.get("slot"), "Should have slot");
        assertNotNull(first.get("tx_index"), "Should have tx_index");
        assertNotNull(first.get("cert_index"), "Should have cert_index");

        // Verify we find the abstain delegation
        boolean found = false;
        for (int i = 0; i < drepDelegations.size(); i++) {
            if ("abstain".equals(drepDelegations.get(i).get("drep_type").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Should find abstain DRep delegation");

        log.info("DRep delegations: {} entries", drepDelegations.size());
    }

    // --- Step 4: Verify pools and pool-retirements endpoints return 200 ---

    @Test
    @Order(7)
    void verifyPoolsEndpoint() throws Exception {
        JsonNode pools = getJson("accounts/pools");
        assertTrue(pools.isArray(), "Should return array");
        // May or may not have pools depending on whether genesis pool registration
        // is tracked — just verify endpoint works
        log.info("Pools: {} entries", pools.size());
    }

    @Test
    @Order(8)
    void verifyPoolRetirementsEndpoint() throws Exception {
        JsonNode retirements = getJson("accounts/pool-retirements");
        assertTrue(retirements.isArray(), "Should return array");
        log.info("Pool retirements: {} entries", retirements.size());
    }

    // --- Step 5: Pagination ---

    @Test
    @Order(9)
    void verifyPagination() throws Exception {
        // Request page 1 with count 1
        JsonNode page1 = getJson("accounts/registrations?page=1&count=1");
        assertTrue(page1.isArray(), "Should return array");
        assertTrue(page1.size() <= 1, "Page with count=1 should have at most 1 entry");

        // Request a very high page — should return empty array
        JsonNode empty = getJson("accounts/registrations?page=9999&count=20");
        assertTrue(empty.isArray(), "Should return array");
        assertEquals(0, empty.size(), "Very high page should be empty");

        log.info("Pagination verified");
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
        assertEquals(200, response.statusCode(), "GET " + path + " failed (" + response.statusCode() + "): " + response.body());
        return mapper.readTree(response.body());
    }
}
