package com.arcade.apitests;

import com.arcade.apitests.support.Api.PlayerRef;
import com.arcade.apitests.support.Db;
import com.arcade.apitests.support.Http;
import com.arcade.apitests.support.Json;
import com.arcade.apitests.support.ProviderJournal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static com.arcade.apitests.support.Api.balance;
import static com.arcade.apitests.support.Api.deposit;
import static com.arcade.apitests.support.Api.expect;
import static com.arcade.apitests.support.Api.finishSession;
import static com.arcade.apitests.support.Api.limit;
import static com.arcade.apitests.support.Api.newKey;
import static com.arcade.apitests.support.Api.newPlayer;
import static com.arcade.apitests.support.Api.setLimit;
import static com.arcade.apitests.support.Api.startSession;
import static com.arcade.apitests.support.Api.startSessionOk;
import static com.arcade.apitests.support.Api.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

/** Requests of one player fired at the same moment: no lost update, no double spend, no double payout. */
@DisplayName("Concurrency")
class ConcurrencyTest extends ApiTest {

    @Test
    @DisplayName("CON-01 parallel deposits with one key execute once; the rest are in progress (409) or replays")
    void sameKeyInParallel() throws Exception {
        PlayerRef player = newPlayer();
        String key = newKey();

        List<Http.Resp> results = parallel(10, i -> deposit(player.id(), "50.00", key));

        assertThat(results).extracting(Http.Resp::status).containsOnly(200, 409);
        assertThat(results).filteredOn(r -> r.status() == 200 && r.header("Idempotent-Replayed") == null)
                .as("executed requests").hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS"));
        assertThat(balance(player.id())).isEqualByComparingTo("50.00");
        assertThat(Db.transactions(player.id(), "DEPOSIT")).hasSize(1);
        assertThat(ProviderJournal.calls(ProviderJournal.PAYMENTS, player.id())).hasSize(1);
    }

    @Test
    @DisplayName("CON-02 parallel withdrawals never overdraw the balance")
    void withdrawalsInParallel() throws Exception {
        PlayerRef player = newPlayer("100.00");

        List<Http.Resp> results = parallel(10, i -> withdraw(player.id(), "30.00", newKey()));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(3);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertThat(r.code()).as("%s", r).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(balance(player.id())).isEqualByComparingTo("10.00");
        assertThat(Db.transactions(player.id(), "WITHDRAWAL")).hasSize(3);
        assertThat(ProviderJournal.calls(ProviderJournal.PAYOUTS, player.id())).hasSize(3);
    }

    @Test
    @DisplayName("CON-03 parallel deposits with different keys are all credited (no lost update)")
    void depositsInParallel() throws Exception {
        PlayerRef player = newPlayer();

        List<Http.Resp> results = parallel(10, i -> deposit(player.id(), "25.00", newKey()));

        assertThat(results).extracting(Http.Resp::status).containsOnly(200);
        assertThat(balance(player.id())).isEqualByComparingTo("250.00");
        assertThat(Db.transactions(player.id(), "DEPOSIT")).hasSize(10);
    }

    @Test
    @DisplayName("CON-04 parallel deposits cannot exceed a deposit limit together")
    void depositLimitInParallel() throws Exception {
        PlayerRef player = newPlayer();
        expect(setLimit(player.id(), "daily", "100.00"), 200);

        List<Http.Resp> results = parallel(5, i -> deposit(player.id(), "30.00", newKey()));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(3);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertThat(r.code()).as("%s", r).isEqualTo("DEPOSIT_LIMIT_EXCEEDED"));
        assertThat(balance(player.id())).isEqualByComparingTo("90.00");
        assertThat(Json.dec(limit(player.id(), "DAILY"), "used")).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("CON-05 parallel starts open one session and take one bet")
    void startsInParallel() throws Exception {
        PlayerRef player = newPlayer("100.00");

        List<Http.Resp> results = parallel(8, i -> startSession(player.id(), "crash", "10.00"));

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 201)
                .allSatisfy(r -> assertThat(r.code()).as("%s", r).isEqualTo("ACTIVE_SESSION_EXISTS"));
        assertThat(balance(player.id())).isEqualByComparingTo("90.00");
        assertThat(Db.activeSessions(player.id())).isEqualTo(1);
        assertThat(Db.transactions(player.id(), "BET")).hasSize(1);
    }

    @Test
    @DisplayName("CON-06 parallel finishes of one session credit the win once")
    void finishesInParallel() throws Exception {
        PlayerRef player = newPlayer("100.00");
        long id = startSessionOk(player.id(), "10.00").get("id").asLong();

        List<Http.Resp> results = parallel(8, i -> finishSession(id, "20.00"));

        assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 200)
                .allSatisfy(r -> assertThat(r.code()).as("%s", r).isEqualTo("SESSION_NOT_ACTIVE"));
        assertThat(balance(player.id())).isEqualByComparingTo("110.00");
        assertThat(Db.transactions(player.id(), "WIN")).hasSize(1);
    }

    @Test
    @DisplayName("CON-07 a bet and a withdrawal racing for the same money: only one succeeds")
    void betAndWithdrawalRace() throws Exception {
        for (int round = 0; round < 3; round++) {
            PlayerRef player = newPlayer("100.00");

            List<Http.Resp> results = parallel(2, i -> i == 0
                    ? startSession(player.id(), "blackjack", "60.00")
                    : withdraw(player.id(), "60.00", newKey()));

            assertThat(results).filteredOn(r -> r.status() / 100 == 2).as("successes of %s", results).hasSize(1);
            assertThat(results).filteredOn(r -> r.status() / 100 != 2)
                    .allSatisfy(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS"));
            assertThat(balance(player.id())).isEqualByComparingTo("40.00");
        }
    }

    /** Runs {@code n} calls released at the same moment and returns their responses in call order. */
    private static List<Http.Resp> parallel(int n, IntFunction<Http.Resp> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Http.Resp>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.apply(index);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Http.Resp> results = new ArrayList<>();
            for (Future<Http.Resp> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
