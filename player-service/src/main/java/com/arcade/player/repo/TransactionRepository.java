package com.arcade.player.repo;

import com.arcade.player.domain.PaymentTransaction;
import com.arcade.player.domain.TxType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface TransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPlayerIdOrderByCreatedAtDesc(Long playerId);

    /** Sum of a player's transactions of one type created strictly after {@code since}; null if none. */
    @Query("""
            select sum(t.amount) from PaymentTransaction t
            where t.playerId = :playerId and t.type = :type and t.createdAt > :since
            """)
    BigDecimal sumSince(@Param("playerId") Long playerId,
                        @Param("type") TxType type,
                        @Param("since") Instant since);
}
