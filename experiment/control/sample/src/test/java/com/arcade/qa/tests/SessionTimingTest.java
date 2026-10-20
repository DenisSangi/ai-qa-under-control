package com.arcade.qa.tests;

import com.arcade.qa.support.Api;
import com.arcade.qa.support.ApiTest;
import com.arcade.qa.support.Events;
import com.arcade.qa.support.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Order(6)
@DisplayName("F-05 Session deadline and expiry")
class SessionTimingTest extends ApiTest {

    static Instant startedAt(JsonNode session) {
        return Instant.parse(session.path("startedAt").asText());
    }

    /** Precondition helper: moves the clock past the deadline and waits until the session is EXPIRED. */
    static void expire(long sessionId) {
        TestClock.advanceTo(startedAt(Api.session(sessionId)).plusSeconds(61));
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if ("EXPIRED".equals(Api.session(sessionId).path("status").asText())) {
                return;
            }
            Events.sleep(200);
        }
        fail("precondition failed: session " + sessionId + " did not expire: " + Api.session(sessionId));
    }

    @Test
    @Tag("SES-04")
    @Tag("SES-12")
    @DisplayName("CL-073 an EXPIRED session does not prevent a new session")
    void newSessionAfterExpiry() {
        long p = Api.fundedPlayer("100");
        long sid = Api.startOk(p, "10").path("id").asLong();
        expire(sid);
        Api.startSession(p, "10");
        assertEquals("EXPIRED", Api.session(sid).path("status").asText(), "status of the first session");
    }
}
