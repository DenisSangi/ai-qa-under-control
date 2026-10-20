package com.arcade.qa.support;

/** Addresses of the running stand (SPEC §10). Each can be overridden with a system property. */
public final class Config {

    public static final String API = prop("api.url", "http://localhost:8080");
    public static final String WS_LEADERBOARD = prop("ws.url", "ws://localhost:8080/ws/leaderboard");
    public static final String KAFKA = prop("kafka.bootstrap", "localhost:9094");
    public static final String STUB = prop("stub.url", "http://localhost:8089");
    public static final String DB_URL = prop("db.url", "jdbc:postgresql://localhost:5433/arcade");
    public static final String DB_USER = "arcade_reader";
    public static final String DB_PASSWORD = "arcade_reader";

    public static final String PLAYER_EVENTS = "player-events";
    public static final String GAME_EVENTS = "game-events";

    /** Id that no player or session has. */
    public static final long UNKNOWN_ID = 999_999_999L;

    private Config() {
    }

    private static String prop(String name, String def) {
        return System.getProperty(name, def);
    }
}
