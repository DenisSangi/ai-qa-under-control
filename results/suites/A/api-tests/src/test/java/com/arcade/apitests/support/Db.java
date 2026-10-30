package com.arcade.apitests.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to the player-service database (user arcade_reader). Used as an independent oracle. */
public final class Db {

    public record Tx(long id, String type, BigDecimal amount, Instant createdAt) {
    }

    public record IdempotencyRow(String operation, BigDecimal amount, String status, String responseBody) {
    }

    public record LeaderRow(long playerId, String username, BigDecimal netResult) {
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private Db() {
    }

    public static <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection c = DriverManager.getConnection(Env.DB_URL, Env.DB_USER, Env.DB_PASSWORD)) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    Object p = params[i];
                    ps.setObject(i + 1, p instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : p);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(mapper.map(rs));
                    }
                    return rows;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    /** The player's ledger in insertion order. */
    public static List<Tx> transactions(long playerId) {
        return query("select id, type, amount, created_at from transactions where player_id = ? order by id",
                rs -> new Tx(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), instant(rs, 4)), playerId);
    }

    public static List<Tx> transactions(long playerId, String type) {
        return transactions(playerId).stream().filter(t -> t.type().equals(type)).toList();
    }

    public static IdempotencyRow idempotencyKey(long playerId, String key) {
        List<IdempotencyRow> rows = query("""
                        select operation, amount, status, response_body from idempotency_keys
                        where player_id = ? and idempotency_key = ?""",
                rs -> new IdempotencyRow(rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)),
                playerId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static String sessionStatus(long sessionId) {
        return query("select status from game_sessions where id = ?", rs -> rs.getString(1), sessionId)
                .stream().findFirst().orElse(null);
    }

    public static long activeSessions(long playerId) {
        return query("select count(*) from game_sessions where player_id = ? and status = 'ACTIVE'",
                rs -> rs.getLong(1), playerId).get(0);
    }

    /**
     * Leaderboard recomputed from the specification: net result (win - bet) of FINISHED sessions
     * finished in [from, to), positive only, best first. Returns every qualifying player, not just 10.
     */
    public static List<LeaderRow> leaderboard(Instant from, Instant to) {
        return query("""
                        select p.id, p.username, sum(s.win_amount - s.bet_amount) as net
                        from game_sessions s join players p on p.id = s.player_id
                        where s.status = 'FINISHED' and s.finished_at >= ? and s.finished_at < ?
                        group by p.id, p.username
                        having sum(s.win_amount - s.bet_amount) > 0
                        order by net desc""",
                rs -> new LeaderRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)), from, to);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
