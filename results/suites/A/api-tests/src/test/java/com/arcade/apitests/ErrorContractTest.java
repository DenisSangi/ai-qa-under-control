package com.arcade.apitests;

import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.getPlayer;
import static org.assertj.core.api.Assertions.assertThat;

/** One stable error contract (ApiError: code, message, timestamp, optional details) and correct 4xx statuses. */
@DisplayName("Error contract")
class ErrorContractTest extends ApiTest {

    @Test
    @DisplayName("ERR-01 an error carries code, message and a timestamp from the service clock; no details unless validation")
    void errorShape() {
        TestClock.ensureShifted(Duration.ofHours(1));
        Instant before = TestClock.now();

        JsonNode error = expectError(getPlayer(9_000_000_000_000L), 404, "NOT_FOUND");
        Instant after = TestClock.now();

        assertThat(Json.instant(error, "timestamp")).isBetween(before, after);
        assertThat(error.has("details")).isFalse();
    }

    static Stream<Arguments> clientMistakes() {
        String json = "application/json";
        return Stream.of(
                Arguments.of("non-numeric player id", "GET", "/api/players/abc", null, null, 400),
                Arguments.of("non-numeric session id", "GET", "/api/sessions/abc", null, null, 400),
                Arguments.of("non-numeric id in a money call", "POST", "/api/players/abc/deposit", json, "{\"amount\":10}", 400),
                Arguments.of("unknown endpoint", "GET", "/api/no-such-endpoint", null, null, 404),
                Arguments.of("unsupported method", "DELETE", "/api/leaderboard", null, null, 405),
                Arguments.of("unsupported content type", "POST", "/api/players", "text/plain", "username=x", 415),
                Arguments.of("negative page", "GET", "/api/players?page=-1", null, null, 400),
                Arguments.of("zero page size", "GET", "/api/players?size=0", null, null, 400),
                Arguments.of("non-numeric page", "GET", "/api/players?page=abc", null, null, 400));
    }

    @ParameterizedTest(name = "{0}: {1} {2} -> {5}")
    @MethodSource("clientMistakes")
    @DisplayName("ERR-02 a client mistake is answered with the matching 4xx and an ApiError, never 500 INTERNAL_ERROR")
    void clientMistakeIsNot500(String name, String method, String path, String contentType, String body, int status) {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("Content-Type", contentType);

        Http.Resp resp = Http.send(method, path, body, headers);

        assertThat(resp.status()).as("status of %s", resp).isEqualTo(status);
        assertThat(resp.code()).as("code of %s", resp).isNotBlank().isNotEqualTo("INTERNAL_ERROR");
    }

    @ParameterizedTest(name = "duration {0}")
    @ValueSource(strings = {"{\"duration\":\"PT0S\"}", "{\"duration\":\"-PT1S\"}", "{\"duration\":\"soon\"}",
            "{\"duration\":null}", "{}"})
    @DisplayName("ERR-03 the test clock refuses a zero, negative or malformed duration and does not move")
    void clockValidation(String body) {
        Duration offset = TestClock.offset();

        JsonNode error = expectError(Http.post("/api/test/clock/advance", body), 400, "VALIDATION_ERROR");

        assertThat(error.path("details").has("duration")).isTrue();
        assertThat(TestClock.offset()).isEqualTo(offset);
    }
}
