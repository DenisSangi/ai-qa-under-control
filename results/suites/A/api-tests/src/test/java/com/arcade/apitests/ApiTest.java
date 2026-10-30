package com.arcade.apitests;

import com.arcade.apitests.support.Env;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.TestClock;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Entry criterion shared by all test classes: the stand is up and the test clock is enabled. */
public abstract class ApiTest {

    private static volatile boolean checked;

    @BeforeAll
    static void standIsReachable() {
        if (checked) {
            return;
        }
        Http.Resp health;
        try {
            health = Http.get("/actuator/health");
        } catch (RuntimeException e) {
            fail("player-service is not reachable at " + Env.API + " - the stand must be running", e);
            return;
        }
        assertThat(health.status()).as("player-service health %s", health).isEqualTo(200);
        assertThat(Http.get("/api/test/clock").status())
                .as("test clock endpoint (app.test-clock.enabled=true) must be available").isEqualTo(200);
        // With the service clock ahead of real time, every "timestamp is on the service clock" check
        // also catches a timestamp taken from the system clock.
        TestClock.ensureShifted(Duration.ofHours(1));
        checked = true;
    }
}
