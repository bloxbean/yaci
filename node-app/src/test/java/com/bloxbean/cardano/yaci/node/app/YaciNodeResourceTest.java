package com.bloxbean.cardano.yaci.node.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class YaciNodeResourceTest {

    @Test
    public void testGetStatus() {
        given()
            .when().get("/api/v1/node/status")
            .then()
                .statusCode(200)
                .body("running", is(false));
    }

    @Test
    public void testGetConfig() {
        given()
            .when().get("/api/v1/node/config")
            .then()
                .statusCode(200)
                .body("protocolMagic", notNullValue());
    }

    @Test
    public void testHealthCheck() {
        given()
            .when().get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
