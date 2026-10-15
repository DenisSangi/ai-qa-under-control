package com.arcade.player.repo;

import com.arcade.player.domain.GameSession;
import com.arcade.player.domain.SessionStatus;
import com.arcade.player.dto.LeaderboardEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    /** Net result (win - bet) of FINISHED sessions finished in [from, to); only positive results. */
    @Query("""
            select new com.arcade.player.dto.LeaderboardEntry(p.id, p.username, sum(s.winAmount - s.betAmount))
            from GameSession s join s.player p
            where s.status = com.arcade.player.domain.SessionStatus.FINISHED
              and s.finishedAt >= :from and s.finishedAt < :to
            group by p.id, p.username
            having sum(s.winAmount - s.betAmount) > 0
            order by sum(s.winAmount - s.betAmount) desc
            """)
    List<LeaderboardEntry> findTop(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    boolean existsByPlayerIdAndStatus(Long playerId, SessionStatus status);

    /** Ids of ACTIVE sessions started at or before {@code startedBefore}. */
    @Query("""
            select s.id from GameSession s
            where s.status = com.arcade.player.domain.SessionStatus.ACTIVE and s.startedAt <= :startedBefore
            """)
    List<Long> findActiveStartedAtOrBefore(@Param("startedBefore") Instant startedBefore);

    /** SELECT ... FOR UPDATE: two concurrent finishes of one session cannot both see it ACTIVE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GameSession s where s.id = :id")
    Optional<GameSession> findByIdForUpdate(@Param("id") Long id);
}
