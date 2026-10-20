package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WireMock admin API of the payment provider stub (SPEC §10). The request journal is only read, never cleared.
 * Behavior changes are global: callers restore them in {@code finally}.
 */
public final class ProviderStub {

    public static final String PAYMENTS = "/payments";
    public static final String PAYOUTS = "/payouts";

    private ProviderStub() {
    }

    /** Bodies of the provider requests to {@code path} for the player, oldest first. */
    public static List<JsonNode> requests(String path, long playerId) {
        Response r = given().baseUri(Config.STUB).get("/__admin/requests?limit=2000");
        assertTrue(r.statusCode() == 200, () -> "provider journal: " + r.statusCode());
        JsonNode all = Json.parse(r.asString()).path("requests");
        List<JsonNode> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            JsonNode req = all.get(i).path("request");
            String url = req.path("url").asText();
            if (!url.equals(path) && !url.startsWith(path + "?")) {
                continue;
            }
            JsonNode body = Json.parse(req.path("body").asText());
            if (body.path("playerId").asLong(-1) == playerId) {
                out.add(body);
            }
        }
        return out;
    }

    public static int count(String path, long playerId) {
        return requests(path, playerId).size();
    }

    public static void setFixedDelay(int millis) {
        Response r = given().baseUri(Config.STUB).contentType(ContentType.JSON)
                .body("{\"fixedDelay\": " + millis + "}").post("/__admin/settings");
        assertTrue(r.statusCode() / 100 == 2, () -> "set provider delay: " + r.statusCode() + " " + r.asString());
    }

    /** Makes {@code path} unreachable: priority-1 mapping with a connection reset. */
    public static void makeUnreachable(String path) {
        String mapping = "{\"priority\": 1, \"request\": {\"method\": \"POST\", \"urlPath\": \"" + path + "\"},"
                + " \"response\": {\"fault\": \"CONNECTION_RESET_BY_PEER\"}}";
        Response r = given().baseUri(Config.STUB).contentType(ContentType.JSON).body(mapping).post("/__admin/mappings");
        assertTrue(r.statusCode() / 100 == 2, () -> "add fault mapping: " + r.statusCode() + " " + r.asString());
    }

    public static void resetMappings() {
        given().baseUri(Config.STUB).post("/__admin/mappings/reset");
    }
}
