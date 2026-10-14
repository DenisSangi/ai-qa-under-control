package com.arcade.player.repo;

import com.arcade.player.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * Claims the key in its own committed transaction. Returns 1 if this request now owns
     * the key, 0 if the key already exists (the database unique constraint decides the race).
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into idempotency_keys (player_id, idempotency_key, operation, amount, status, created_at)
            values (:playerId, :key, :operation, :amount, 'IN_PROGRESS', :createdAt)
            on conflict (player_id, idempotency_key) do nothing
            """, nativeQuery = true)
    int tryInsert(@Param("playerId") Long playerId,
                  @Param("key") String key,
                  @Param("operation") String operation,
                  @Param("amount") BigDecimal amount,
                  @Param("createdAt") Instant createdAt);

    Optional<IdempotencyRecord> findByPlayerIdAndKey(Long playerId, String key);

    @Modifying
    @Transactional
    @Query("delete from IdempotencyRecord r where r.playerId = :playerId and r.key = :key")
    void deleteByPlayerIdAndKey(@Param("playerId") Long playerId, @Param("key") String key);
}
