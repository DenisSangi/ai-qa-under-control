package com.arcade.player.service;

import com.arcade.player.domain.IdempotencyRecord;
import com.arcade.player.domain.TxType;
import com.arcade.player.exception.BusinessException;
import com.arcade.player.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Idempotency-Key for money operations (deposit, withdrawal).
 *
 * <p>Lifecycle of a key: {@link #claim} inserts it as IN_PROGRESS and commits; the money
 * operation calls {@link #complete} inside its own transaction, so the stored response
 * commits together with the balance change; if the operation fails, {@link #release}
 * deletes the key and the same key can be retried. Keys never expire.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern KEY_FORMAT = Pattern.compile("[\\x20-\\x7E]{1,64}");

    private final IdempotencyRepository records;
    private final ObjectMapper mapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository records, ObjectMapper mapper, Clock clock) {
        this.records = records;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void validate(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST,
                    "header " + HEADER + " is required");
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new BusinessException("IDEMPOTENCY_KEY_INVALID", HttpStatus.BAD_REQUEST,
                    HEADER + " must be 1-64 printable ASCII characters");
        }
    }

    /**
     * @return empty if this request owns the key and must execute the operation;
     *         the stored response body if the same request already completed
     */
    public Optional<String> claim(Long playerId, String key, TxType operation, BigDecimal amount) {
        // Two attempts: the key may disappear between the failed insert and the lookup
        // when its owner fails and releases it.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (records.tryInsert(playerId, key, operation.name(), amount, clock.instant()) == 1) {
                return Optional.empty();
            }
            Optional<IdempotencyRecord> existing = records.findByPlayerIdAndKey(playerId, key);
            if (existing.isEmpty()) {
                continue;
            }
            IdempotencyRecord record = existing.get();
            if (!record.matches(operation, amount)) {
                throw new BusinessException("IDEMPOTENCY_KEY_REUSED", HttpStatus.UNPROCESSABLE_ENTITY,
                        HEADER + " '" + key + "' was already used for a different request");
            }
            if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
                throw inProgress(key);
            }
            return Optional.of(record.getResponseBody());
        }
        throw inProgress(key);
    }

    /** Must run inside the money operation's transaction. */
    public void complete(Long playerId, String key, Object response) {
        IdempotencyRecord record = records.findByPlayerIdAndKey(playerId, key)
                .orElseThrow(() -> new IllegalStateException("idempotency key was not claimed: " + key));
        try {
            record.complete(mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    public void release(Long playerId, String key) {
        records.deleteByPlayerIdAndKey(playerId, key);
    }

    private static BusinessException inProgress(String key) {
        return new BusinessException("IDEMPOTENCY_KEY_IN_PROGRESS", HttpStatus.CONFLICT,
                "a request with " + HEADER + " '" + key + "' is still in progress");
    }
}
