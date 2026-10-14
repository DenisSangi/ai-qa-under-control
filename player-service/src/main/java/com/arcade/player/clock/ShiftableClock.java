package com.arcade.player.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The only source of "now" in player-service: every timestamp the service writes or compares
 * comes from here. Normally it is the system clock. With app.test-clock.enabled=true,
 * {@link com.arcade.player.web.TestClockController} can move it forward so that tests check
 * time-based rules (deposit limit windows, cooling-off) without waiting.
 *
 * <p>Forward only: moving back would put records already written into the future. The offset
 * is global for the service, lives in memory and resets on restart.
 */
public class ShiftableClock extends Clock {

    private final Clock base;
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public ShiftableClock(Clock base) {
        this.base = base;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(base.withZone(zone), offset.get());
    }

    public Duration advance(Duration by) {
        return offset.accumulateAndGet(by, Duration::plus);
    }

    public Duration getOffset() {
        return offset.get();
    }
}
