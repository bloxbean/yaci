package com.bloxbean.cardano.yaci.node.app.api.devnet;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for DevnetResource endpoints.
 * The default %test profile does NOT enable dev mode, so all devnet endpoints
 * should return 403. This verifies the guard logic works correctly.
 */
@QuarkusTest
public class DevnetResourceTest {

    @Test
    void epochsShift_shouldReturn403WhenNotInDevMode() {
        given()
            .contentType("application/json")
            .body("{\"epochs\": 4}")
            .when().post("/api/v1/devnet/epochs/shift")
            .then()
                .statusCode(403)
                .body("error", containsString("dev mode"));
    }

    @Test
    void epochsCatchUp_shouldReturn403WhenNotInDevMode() {
        given()
            .contentType("application/json")
            .when().post("/api/v1/devnet/epochs/catch-up")
            .then()
                .statusCode(403)
                .body("error", containsString("dev mode"));
    }

    @Test
    void timeAdvance_shouldReturn403WhenNotInDevMode() {
        given()
            .contentType("application/json")
            .body("{\"slots\": 10}")
            .when().post("/api/v1/devnet/time/advance")
            .then()
                .statusCode(403)
                .body("error", containsString("dev mode"));
    }

    @Test
    void rollback_shouldReturn403WhenNotInDevMode() {
        given()
            .contentType("application/json")
            .body("{\"slot\": 0}")
            .when().post("/api/v1/devnet/rollback")
            .then()
                .statusCode(403)
                .body("error", containsString("dev mode"));
    }

    @Test
    void genesisDownload_shouldReturn403WhenNotInDevMode() {
        given()
            .when().get("/api/v1/devnet/genesis/download")
            .then()
                .statusCode(403);
    }
}
