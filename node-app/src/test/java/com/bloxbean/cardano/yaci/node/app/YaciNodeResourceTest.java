package com.bloxbean.cardano.yaci.node.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
public class YaciNodeResourceTest {

    @Test
    public void testGetStatus() {
        given()
          .when().get("/api/v1/node/status")
          .then()
             .statusCode(200)
             .contentType(ContentType.JSON)
             .body("running", is(false)) // Node should not be running initially
             .body("timestamp", notNullValue());
    }

    @Test
    public void testGetConfig() {
        given()
          .when().get("/api/v1/node/config")
          .then()
             .statusCode(200)
             .contentType(ContentType.JSON)
             .body("protocolMagic", notNullValue());
    }

    @Test
    public void testHealthCheck() {
        given()
          .when().get("/q/health/ready")
          .then()
             .statusCode(200)
             .contentType(ContentType.JSON)
             .body("status", is("UP"))
             .body("checks.find { it.name == 'yaci-node' }.status", is("UP"));
    }

    // @Test  // Temporarily disabled - needs network connectivity
    public void testNodeAPIEndpoints() {
        // Test that all endpoints are accessible and return expected HTTP status codes

        // Status endpoint should always work
        given()
          .when().get("/api/v1/node/status")
          .then()
             .statusCode(200)
             .contentType(ContentType.JSON);

        // Config endpoint should always work
        given()
          .when().get("/api/v1/node/config")
          .then()
             .statusCode(200)
             .contentType(ContentType.JSON);

        // Tip endpoint might return 404 if no data, but should not error
        given()
          .when().get("/api/v1/node/tip")
          .then()
             .statusCode(anyOf(is(200), is(404)));

        // Start endpoint should respond (might fail due to network issues)
        given()
          .when().post("/api/v1/node/start")
          .then()
             .statusCode(anyOf(is(200), is(409), is(500)))
             .contentType(ContentType.JSON);

        // Stop endpoint should respond
        given()
          .when().post("/api/v1/node/stop")
          .then()
             .statusCode(anyOf(is(200), is(409), is(500)))
             .contentType(ContentType.JSON);
    }
}
