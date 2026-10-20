package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Config;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.Journal;
import com.arcade.qa.support.Snapshot;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.arcade.qa.support.Asserts.assertAmount;
import static com.arcade.qa.support.Asserts.assertError;
import static com.arcade.qa.support.Asserts.assertNotReplayed;
import static com.arcade.qa.support.Asserts.assertReplayOf;
import static com.arcade.qa.support.Asserts.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Order(2)
@DisplayName("F-07 Idempotency of money operations")
class IdempotencyKeyTest extends ApiTest {

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    @Tag("GEN-09")
    @DisplayName("CL-085 keys of 1 and 64 printable ASCII characters are accepted")
    void keyLengthBoundsAccepted() {
        long p = Api.createPlayer();
        assertStatus(Api.deposit(p, "x", "10"), 200);
        String k64 = (Api.key() + repeat('~', 64)).substring(0, 64);
        assertStatus(Api.deposit(p, k64, "10"), 200);
        assertStatus(Api.startSession(repeat('S', 64), p, "slots", "1"), 201);
    }

    @Test
    @Tag("GEN-14")
    @DisplayName("CL-089 amounts are compared by value: 50 then 50.00 with one key is a replay")
    void amountComparedByValue() {
        long p = Api.createPlayer();
        String key = Api.key();
        Response first = Api.deposit(p, key, "50");
        assertStatus(first, 200);
        Response second = Api.deposit(p, key, "50.00");
        assertReplayOf(first, second);
        assertAmount(new BigDecimal("50"), Api.balance(p), "balance");
        assertEquals(1, Journal.records(p, "DEPOSIT").size(), "DEPOSIT records");
    }

    @Test
    @Tag("GEN-12")
    @DisplayName("CL-093 session start repeated with its key while the session is active: 409 ACTIVE_SESSION_EXISTS; no second BET, no second event")
    void sameKeyWhileActive() {
        long p = Api.fundedPlayer("100");
        String key = Api.key();
        Response first = Api.startSession(key, p, "poker", "10");
        assertStatus(first, 201);
        assertNotReplayed(first);
        Events.awaitOne(Config.GAME_EVENTS, p, "SESSION_STARTED", EVENT_TIMEOUT);
        Snapshot before = Snapshot.of(p);
        assertError(Api.startSession(key, p, "poker", "10"), 409, "ACTIVE_SESSION_EXISTS");
        before.assertUnchanged();
        assertEquals(1, Journal.records(p, "BET").size(), "BET records");
    }
}
