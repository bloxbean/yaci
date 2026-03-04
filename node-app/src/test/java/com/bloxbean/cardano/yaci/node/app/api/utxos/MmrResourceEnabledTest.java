package com.bloxbean.cardano.yaci.node.app.api.utxos;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
public class MmrResourceEnabledTest {

    @Test
    public void testRoot_200_json() {
        given()
                .when().get("/api/v1/utxo/mmr/root")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("root", notNullValue())
                .body("leafCount", notNullValue());
    }

    @Test
    public void testStatus_hasMmrSection() {
        given()
                .when().get("/api/v1/status")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("utxo.mmr.root", notNullValue())
                .body("utxo.mmr.leafCount", notNullValue())
                .body("utxo.enabled", is(true));
    }
}

