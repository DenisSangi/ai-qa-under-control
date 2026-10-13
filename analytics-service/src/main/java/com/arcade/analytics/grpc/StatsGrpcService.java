package com.arcade.analytics.grpc;

import com.arcade.analytics.domain.PlayerStats;
import com.arcade.analytics.repo.PlayerStatsRepository;
import com.arcade.grpc.stats.PlayerStatsRequest;
import com.arcade.grpc.stats.PlayerStatsResponse;
import com.arcade.grpc.stats.StatsServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * gRPC server (port 6565). Reflection is enabled by the starter, so grpcurl
 * can discover the schema: grpcurl -plaintext localhost:6565 list
 */
@GrpcService
public class StatsGrpcService extends StatsServiceGrpc.StatsServiceImplBase {

    private final PlayerStatsRepository repo;

    public StatsGrpcService(PlayerStatsRepository repo) {
        this.repo = repo;
    }

    @Override
    public void getPlayerStats(PlayerStatsRequest request, StreamObserver<PlayerStatsResponse> observer) {
        Optional<PlayerStats> found = repo.findById(request.getPlayerId());
        if (found.isEmpty()) {
            observer.onError(Status.NOT_FOUND
                    .withDescription("no stats for player " + request.getPlayerId())
                    .asRuntimeException());
            return;
        }
        PlayerStats s = found.get();
        BigDecimal rtp = s.getTotalBet().signum() == 0
                ? BigDecimal.ZERO
                : s.getTotalWin().multiply(BigDecimal.valueOf(100))
                .divide(s.getTotalBet(), 2, RoundingMode.HALF_UP);
        observer.onNext(PlayerStatsResponse.newBuilder()
                .setPlayerId(s.getPlayerId())
                .setTotalSessions(s.getTotalSessions())
                .setTotalBet(s.getTotalBet().toPlainString())
                .setTotalWin(s.getTotalWin().toPlainString())
                .setRtp(rtp.toPlainString())
                .build());
        observer.onCompleted();
    }
}
