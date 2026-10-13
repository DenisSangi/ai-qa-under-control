package com.arcade.player.web;

import com.arcade.player.domain.DepositLimitPeriod;
import com.arcade.player.dto.DepositLimitDto;
import com.arcade.player.dto.DepositLimitRequest;
import com.arcade.player.service.DepositLimitService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/players/{id}/deposit-limits")
public class DepositLimitController {

    private final DepositLimitService depositLimits;

    public DepositLimitController(DepositLimitService depositLimits) {
        this.depositLimits = depositLimits;
    }

    @GetMapping
    public List<DepositLimitDto> list(@PathVariable Long id) {
        return depositLimits.list(id);
    }

    @PutMapping("/{period}")
    public DepositLimitDto set(@PathVariable Long id, @PathVariable String period,
                               @Valid @RequestBody DepositLimitRequest request) {
        return depositLimits.set(id, DepositLimitPeriod.fromPath(period), request.amount());
    }

    @DeleteMapping("/{period}")
    public DepositLimitDto remove(@PathVariable Long id, @PathVariable String period) {
        return depositLimits.remove(id, DepositLimitPeriod.fromPath(period));
    }
}
