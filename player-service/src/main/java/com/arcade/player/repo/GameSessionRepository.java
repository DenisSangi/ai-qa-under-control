package com.arcade.player.repo;

import com.arcade.player.domain.GameSession;
import com.arcade.player.dto.LeaderboardEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    @Query("""
            select new com.arcade.player.dto.LeaderboardEntry(p.id, p.username, sum(s.winAmount))
            from GameSession s join s.player p
            where s.status = com.arcade.player.domain.SessionStatus.FINISHED
            group by p.id, p.username
            order by sum(s.winAmount) desc
            """)
    List<LeaderboardEntry> findTop(Pageable pageable);

    /** SELECT ... FOR UPDATE: two concurrent finishes of one session cannot both see it ACTIVE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GameSession s where s.id = :id")
    Optional<GameSession> findByIdForUpdate(@Param("id") Long id);
}
