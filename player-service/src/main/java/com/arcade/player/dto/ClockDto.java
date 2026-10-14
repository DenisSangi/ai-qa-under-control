package com.arcade.player.dto;

import java.time.Instant;

/** {@code offset} is the total shift from real time as an ISO-8601 duration. */
public record ClockDto(Instant now, String offset) {
}
