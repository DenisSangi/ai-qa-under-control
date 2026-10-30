package com.arcade.apitests.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Client of the plain (non-STOMP) WebSocket /ws/leaderboard; collects every text message as JSON. */
public final class LeaderboardSocket implements AutoCloseable {

    private final LinkedBlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    private final List<JsonNode> received = new ArrayList<>();
    private WebSocket socket;

    private LeaderboardSocket() {
    }

    public static LeaderboardSocket connect() {
        LeaderboardSocket client = new LeaderboardSocket();
        try {
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(Env.WS), client.new Listener())
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("cannot connect to " + Env.WS, e);
        }
        return client;
    }

    /** Waits for the first message matching {@code filter}; null if none arrived in time. */
    public JsonNode await(Predicate<JsonNode> filter, Duration timeout) throws InterruptedException {
        for (JsonNode m : received) {
            if (filter.test(m)) {
                return m;
            }
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode m = inbox.poll(Duration.between(Instant.now(), deadline).toMillis(), TimeUnit.MILLISECONDS);
            if (m == null) {
                break;
            }
            received.add(m);
            if (filter.test(m)) {
                return m;
            }
        }
        return null;
    }

    /** Every message received until {@code window} passes. */
    public List<JsonNode> during(Duration window) throws InterruptedException {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            JsonNode m = inbox.poll(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
            if (m != null) {
                received.add(m);
            }
        }
        return List.copyOf(received);
    }

    /** Forgets everything received so far. */
    public void clear() {
        received.clear();
        inbox.clear();
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            socket.abort();
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                inbox.add(Json.parse(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
