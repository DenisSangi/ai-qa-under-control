package com.arcade.player.repo;

import com.arcade.player.domain.Player;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Page<Player> findByCountry(String country, Pageable pageable);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * SELECT ... FOR UPDATE: every balance change locks the player row first, so concurrent
     * deposits, withdrawals and bets of one player run one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Player p where p.id = :id")
    Optional<Player> findByIdForUpdate(@Param("id") Long id);
}
