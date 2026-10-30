package com.arcade.qa.support;

import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Sends requests at the same moment (GEN-26): all tasks wait on one start gate. */
public final class Parallel {

    private Parallel() {
    }

    public static List<Response> run(int n, IntFunction<Response> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.apply(idx);
                }));
            }
            gate.countDown();
            List<Response> out = new ArrayList<>();
            for (Future<Response> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError("parallel requests failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    public static long count(List<Response> responses, int status) {
        return responses.stream().filter(r -> r.statusCode() == status).count();
    }

    public static String describe(List<Response> responses) {
        return responses.stream().map(r -> r.statusCode() + " " + Json.abbreviate(r.asString())).toList().toString();
    }
}
