package com.arcade.qa.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to the journal: table {@code transactions} (SPEC §2, §11). */
public final class Journal {

    public record Tx(long id, String type, BigDecimal amount, Instant createdAt) {
    }

    private static Connection connection;

    private Journal() {
    }

    private static synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            connection = DriverManager.getConnection(Config.DB_URL, Config.DB_USER, Config.DB_PASSWORD);
            connection.setReadOnly(true);
            connection.setAutoCommit(true);
        }
        return connection;
    }

    public static synchronized List<Tx> records(long playerId) {
        String sql = "SELECT id, type, amount, created_at FROM transactions WHERE player_id = ? ORDER BY id";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setLong(1, playerId);
            List<Tx> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tx(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                            rs.getTimestamp(4).toInstant()));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the journal", e);
        }
    }

    public static List<Tx> records(long playerId, String type) {
        return records(playerId).stream().filter(t -> t.type().equals(type)).toList();
    }

    /** GEN-25: sum of DEPOSIT, WIN, REFUND − sum of WITHDRAWAL, BET. */
    public static BigDecimal journalBalance(long playerId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Tx t : records(playerId)) {
            switch (t.type()) {
                case "DEPOSIT", "WIN", "REFUND" -> sum = sum.add(t.amount());
                case "WITHDRAWAL", "BET" -> sum = sum.subtract(t.amount());
                default -> throw new AssertionError("unknown journal type " + t.type() + " for player " + playerId);
            }
        }
        return sum;
    }
}
