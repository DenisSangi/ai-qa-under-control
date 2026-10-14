package com.arcade.player.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Instant timestamp, Map<String, String> details) {
}
