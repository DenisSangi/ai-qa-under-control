package com.arcade.player.web;

import com.arcade.player.clock.ShiftableClock;
import com.arcade.player.dto.AdvanceClockRequest;
import com.arcade.player.dto.ClockDto;
import com.arcade.player.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Test hook, absent unless app.test-clock.enabled=true. See {@link ShiftableClock}. */
@RestController
@RequestMapping("/api/test/clock")
@ConditionalOnProperty(name = "app.test-clock.enabled", havingValue = "true")
public class TestClockController {

    private final ShiftableClock clock;

    public TestClockController(ShiftableClock clock) {
        this.clock = clock;
    }

    @GetMapping
    public ClockDto get() {
        return snapshot();
    }

    @PostMapping("/advance")
    public ClockDto advance(@Valid @RequestBody AdvanceClockRequest request) {
        if (request.duration().isNegative() || request.duration().isZero()) {
            throw new BusinessException("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, "request validation failed",
                    Map.of("duration", "must be positive"));
        }
        clock.advance(request.duration());
        return snapshot();
    }

    private ClockDto snapshot() {
        return new ClockDto(clock.instant(), clock.getOffset().toString());
    }
}
