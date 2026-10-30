package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.arcade.apitests.support.Api.createPlayer;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.expectError;
import static com.arcade.apitests.support.Api.getPlayer;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Players")
class PlayerApiTest extends ApiTest {

    @Test
    @DisplayName("PL-01 a new player gets defaults, an upper-cased country and createdAt on the service clock")
    void createsPlayerWithDefaults() {
        TestClock.ensureShifted(Duration.ofHours(1));
        String username = unique("pl01");
        String email = username + "@apitest.example.com";
        Instant before = TestClock.now();

        Http.Resp created = expect(createPlayer(username, email, "cy"), 201);
        Instant after = TestClock.now();

        JsonNode body = created.json();
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(Json.text(body, "username")).isEqualTo(username);
        assertThat(Json.text(body, "email")).isEqualTo(email);
        assertThat(Json.text(body, "country")).isEqualTo("CY");
        assertThat(body.get("level").asInt()).isEqualTo(1);
        assertThat(Json.dec(body, "balance")).isZero();
        assertThat(Json.instant(body, "createdAt")).isBetween(before, after);

        JsonNode fetched = expect(getPlayer(body.get("id").asLong()), 200).json();
        assertThat(fetched.get("id").asLong()).isEqualTo(body.get("id").asLong());
        assertThat(Json.text(fetched, "username")).isEqualTo(username);
        assertThat(Json.text(fetched, "email")).isEqualTo(email);
        assertThat(Json.text(fetched, "country")).isEqualTo("CY");
        assertThat(fetched.get("level").asInt()).isEqualTo(1);
        assertThat(Json.dec(fetched, "balance")).isZero();
        assertThat(Json.instant(fetched, "createdAt"))
                .isCloseTo(Json.instant(body, "createdAt"), within(1, ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("PL-02 username and e-mail are unique")
    void rejectsDuplicates() {
        PlayerRef existing = newPlayer();

        expectError(createPlayer(existing.username(), unique("other") + "@apitest.example.com", "DE"),
                409, "DUPLICATE_USERNAME");
        expectError(createPlayer(unique("other"), existing.email(), "DE"), 409, "DUPLICATE_EMAIL");
    }

    static Stream<Arguments> invalidPlayers() {
        String ok = "valid_name";
        return Stream.of(
                Arguments.of("ab", "a@apitest.example.com", "DE", "username"),
                Arguments.of("u".repeat(51), "a@apitest.example.com", "DE", "username"),
                Arguments.of("   ", "a@apitest.example.com", "DE", "username"),
                Arguments.of(ok, "not-an-email", "DE", "email"),
                Arguments.of(ok, "", "DE", "email"),
                Arguments.of(ok, "a@apitest.example.com", "D", "country"),
                Arguments.of(ok, "a@apitest.example.com", "DEU", "country"));
    }

    @ParameterizedTest(name = "username=''{0}'' email=''{1}'' country=''{2}'' -> {3}")
    @MethodSource("invalidPlayers")
    @DisplayName("PL-03 invalid fields are refused with a detail per field")
    void validatesFields(String username, String email, String country, String field) {
        JsonNode error = expectError(createPlayer(username, email, country), 400, "VALIDATION_ERROR");
        assertThat(error.path("details").has(field)).as("details of %s", error).isTrue();
    }

    @Test
    @DisplayName("PL-03 missing fields are refused")
    void requiresAllFields() {
        JsonNode error = expectError(Http.post("/api/players", "{}"), 400, "VALIDATION_ERROR");
        assertThat(error.path("details").has("username")).isTrue();
        assertThat(error.path("details").has("email")).isTrue();
        assertThat(error.path("details").has("country")).isTrue();
    }

    @Test
    @DisplayName("PL-03 a 50-character username is accepted")
    void acceptsMaxLengthUsername() {
        String username = (unique("pl03") + "x".repeat(50)).substring(0, 50);
        expect(createPlayer(username, username + "@apitest.example.com", "DE"), 201);
    }

    @Test
    @DisplayName("PL-04 an unknown player is 404")
    void unknownPlayer() {
        expectError(getPlayer(9_000_000_000_000L), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("PL-05 the list is paginated")
    void paginates() {
        JsonNode first = expect(Http.get("/api/players?page=0&size=3"), 200).json();
        JsonNode second = expect(Http.get("/api/players?page=1&size=3"), 200).json();

        assertThat(first.path("content")).hasSize(3);
        assertThat(first.path("page").path("size").asInt()).isEqualTo(3);
        assertThat(first.path("page").path("number").asInt()).isZero();
        assertThat(second.path("page").path("number").asInt()).isEqualTo(1);
        long total = first.path("page").path("totalElements").asLong();
        assertThat(total).isGreaterThanOrEqualTo(6);
        assertThat(first.path("page").path("totalPages").asLong()).isEqualTo((total + 2) / 3);

        assertThat(expect(Http.get("/api/players"), 200).json().path("page").path("size").asInt())
                .as("default page size").isEqualTo(20);

        Set<Long> ids = new HashSet<>();
        first.path("content").forEach(p -> ids.add(p.get("id").asLong()));
        second.path("content").forEach(p -> assertThat(ids).doesNotContain(p.get("id").asLong()));
    }

    @Test
    @DisplayName("PL-05 the country filter returns only players of that country")
    void filtersByCountry() {
        String username = unique("pl05");
        long id = expect(createPlayer(username, username + "@apitest.example.com", "MT"), 201).json().get("id").asLong();

        JsonNode page = expect(Http.get("/api/players?country=MT&size=5000"), 200).json();

        List<Long> ids = new ArrayList<>();
        for (JsonNode p : page.path("content")) {
            assertThat(Json.text(p, "country")).as("player %s", p).isEqualTo("MT");
            ids.add(p.get("id").asLong());
        }
        assertThat(ids).contains(id);
    }
}
