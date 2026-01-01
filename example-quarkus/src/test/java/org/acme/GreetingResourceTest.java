package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {
    @Test
    void createAppendAndReadTextStream() {
        String streamPath = "/streams/test-" + UUID.randomUUID();

        given()
                .header("Content-Type", "text/plain")
                .when().put(streamPath)
                .then()
                .statusCode(201);

        given()
                .header("Content-Type", "text/plain")
                .body("hello")
                .when().post(streamPath)
                .then()
                .statusCode(204);

        given()
                .when().get(streamPath + "?offset=-1")
                .then()
                .statusCode(200)
                .body(is("hello"));
    }
}
