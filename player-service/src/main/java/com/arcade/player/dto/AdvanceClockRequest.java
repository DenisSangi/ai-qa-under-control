package com.arcade.player.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

/** {@code duration} is an ISO-8601 duration, e.g. "PT25H" or "P7D". */
public record AdvanceClockRequest(
        @NotNull @Schema(type = "string", example = "PT25H", description = "ISO-8601 duration") Duration duration
) {
}
