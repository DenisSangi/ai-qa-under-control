package com.arcade.player.repo;

import com.arcade.player.domain.Player;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Page<Player> findByCountry(String country, Pageable pageable);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
