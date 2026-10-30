package com.arcade.apitests.support;

/** Addresses of the running stand; each can be overridden with a system property of the same name. */
public final class Env {

    public static final String API = prop("api.baseUrl", "http://localhost:8080");
    public static final String WS = prop("ws.url", "ws://localhost:8080/ws/leaderboard");
    public static final String KAFKA = prop("kafka.bootstrap", "localhost:9094");
    public static final String STUB = prop("stub.baseUrl", "http://localhost:8089");
    public static final String DB_URL = prop("db.url", "jdbc:postgresql://localhost:5433/arcade");
    public static final String DB_USER = prop("db.user", "arcade_reader");
    public static final String DB_PASSWORD = prop("db.password", "arcade_reader");

    private Env() {
    }

    private static String prop(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
