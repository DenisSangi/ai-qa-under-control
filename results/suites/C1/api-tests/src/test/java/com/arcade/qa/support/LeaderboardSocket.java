package com.arcade.qa.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Client of {@code /ws/leaderboard} that records every message (LDB-06). */
public final class LeaderboardSocket implements AutoCloseable {

    public record Message(long receivedAtNanos, JsonNode body) {
    }

    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    private WebSocket ws;

    private LeaderboardSocket() {
    }

    public static LeaderboardSocket open() {
        LeaderboardSocket s = new LeaderboardSocket();
        try {
            s.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(Config.WS_LEADERBOARD), s.listener())
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("cannot connect to " + Config.WS_LEADERBOARD, e);
        }
        return s;
    }

    private WebSocket.Listener listener() {
        return new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                synchronized (partial) {
                    partial.append(data);
                    if (last) {
                        messages.add(new Message(System.nanoTime(), Json.parse(partial.toString())));
                        partial.setLength(0);
                    }
                }
                webSocket.request(1);
                return null;
            }
        };
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public int size() {
        return messages.size();
    }

    /** First message at index ≥ {@code from} whose body matches, or null after the timeout. */
    public Message await(int from, Predicate<JsonNode> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<Message> all = messages();
            for (int i = from; i < all.size(); i++) {
                if (match.test(all.get(i).body())) {
                    return all.get(i);
                }
            }
            if (System.nanoTime() > deadline) {
                return null;
            }
            Events.sleep(50);
        }
    }

    /** Waits until no message has arrived for {@code quiet}, at most {@code max}. */
    public void awaitQuiet(Duration quiet, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        int seen = size();
        long lastChange = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Events.sleep(100);
            if (size() != seen) {
                seen = size();
                lastChange = System.nanoTime();
            } else if (System.nanoTime() - lastChange >= quiet.toNanos()) {
                return;
            }
        }
    }

    @Override
    public void close() {
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            ws.abort();
        }
    }
}
