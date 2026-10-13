package com.arcade.player.repo;

import com.arcade.player.domain.GameSession;
import com.arcade.player.dto.LeaderboardEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    @Query("""
            select new com.arcade.player.dto.LeaderboardEntry(p.id, p.username, sum(s.winAmount))
            from GameSession s join s.player p
            where s.status = com.arcade.player.domain.SessionStatus.FINISHED
            group by p.id, p.username
            order by sum(s.winAmount) desc
            """)
    List<LeaderboardEntry> findTop(Pageable pageable);
}
