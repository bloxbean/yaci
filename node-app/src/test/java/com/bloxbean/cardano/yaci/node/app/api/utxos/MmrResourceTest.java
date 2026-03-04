package com.bloxbean.cardano.yaci.node.app.api.utxos;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
public class MmrResourceTest {

    @Test
    public void testRootEndpoint_availableOrNotFound() {
        given()
                .when().get("/api/v1/utxo/mmr/root")
                .then()
                .statusCode(anyOf(is(200), is(404)));
    }

    @Test
    public void testProofEndpoint_notFoundWithoutMmrOrLeaf() {
        given()
                .when().get("/api/v1/utxo/mmr/proof/" + "ab".repeat(32) + "/0")
                .then()
                .statusCode(404);
    }

    @Test
    public void testLeafIndexEndpoint_notFoundWithoutMmrOrLeaf() {
        given()
                .when().get("/api/v1/utxo/mmr/leaf-index/" + "ab".repeat(32) + "/0")
                .then()
                .statusCode(404);
    }

    @Test
    public void testStatusIncludesUtxoSection() {
        given()
                .when().get("/api/v1/status")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("utxo.enabled", notNullValue());
    }
}
