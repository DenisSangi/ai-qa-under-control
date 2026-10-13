package com.arcade.player.repo;

import com.arcade.player.domain.DepositLimit;
import com.arcade.player.domain.DepositLimitPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepositLimitRepository extends JpaRepository<DepositLimit, Long> {

    List<DepositLimit> findByPlayerId(Long playerId);

    Optional<DepositLimit> findByPlayerIdAndPeriod(Long playerId, DepositLimitPeriod period);
}
