package com.arcade.player.service;

import com.arcade.grpc.stats.PlayerStatsRequest;
import com.arcade.grpc.stats.PlayerStatsResponse;
import com.arcade.grpc.stats.StatsServiceGrpc;
import com.arcade.player.dto.PlayerStatsDto;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.exception.NotFoundException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client of analytics-service (contract: proto/src/main/proto/stats.proto).
 */
@Service
public class StatsClient {

    @GrpcClient("stats-service")
    private StatsServiceGrpc.StatsServiceBlockingStub stub;

    public PlayerStatsDto getStats(long playerId) {
        try {
            PlayerStatsResponse r = stub
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getPlayerStats(PlayerStatsRequest.newBuilder().setPlayerId(playerId).build());
            return new PlayerStatsDto(r.getPlayerId(), r.getTotalSessions(),
                    r.getTotalBet(), r.getTotalWin(), r.getRtp());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new NotFoundException("no stats yet for player " + playerId);
            }
            throw new BusinessException("ANALYTICS_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                    "analytics-service call failed: " + e.getStatus().getCode());
        }
    }
}
