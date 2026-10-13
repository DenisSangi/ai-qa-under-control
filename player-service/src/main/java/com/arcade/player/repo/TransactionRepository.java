package com.arcade.player.repo;

import com.arcade.player.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    List<PaymentTransaction> findByPlayerIdOrderByCreatedAtDesc(Long playerId);
}
