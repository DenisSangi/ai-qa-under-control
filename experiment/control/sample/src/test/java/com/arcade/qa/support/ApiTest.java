package com.arcade.qa.support;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base of every autotest class: checks that the stand is reachable (entry criteria) and starts the event
 * collector before the first operation.
 */
public abstract class ApiTest {

    protected static final Duration EVENT_TIMEOUT = Duration.ofSeconds(10);

    private static boolean initialized;

    @BeforeAll
    static void initEnvironment() {
        synchronized (ApiTest.class) {
            if (initialized) {
                return;
            }
            RestAssured.config = RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", 5_000)
                    .setParam("http.socket.timeout", 30_000));
            try {
                TestClock.now();
            } catch (Throwable e) {
                fail("the system is not reachable at " + Config.API + " — testing stopped: " + e.getMessage());
            }
            Events.start();
            initialized = true;
        }
    }
}
