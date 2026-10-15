package com.arcade.player.service;

import com.arcade.player.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

/**
 * Client of the external payment provider. In this trainer the provider is a
 * WireMock stub (docker-compose service "wiremock"): payments over 10000 and
 * payouts over 5000 are declined.
 */
@Service
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${payment.base-url}") String baseUrl, RestClient.Builder builder) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** {@code reference} identifies the operation: a retry of one operation sends the same value. */
    public record PaymentRequest(Long playerId, BigDecimal amount, String reference) {
    }

    public record PaymentResponse(String status, String transactionId, String reason) {
    }

    /** Takes money from the player's payment method (deposit). */
    public String charge(Long playerId, BigDecimal amount, String reference) {
        return send("/payments", "payment", "PAYMENT_DECLINED", new PaymentRequest(playerId, amount, reference));
    }

    /** Sends money to the player's payment method (withdrawal). */
    public String payout(Long playerId, BigDecimal amount, String reference) {
        return send("/payouts", "payout", "PAYOUT_DECLINED", new PaymentRequest(playerId, amount, reference));
    }

    private String send(String uri, String operation, String declinedCode, PaymentRequest request) {
        try {
            PaymentResponse response = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PaymentResponse.class);
            if (response == null || !"APPROVED".equals(response.status())) {
                throw new BusinessException(declinedCode, HttpStatus.UNPROCESSABLE_ENTITY,
                        operation + " was not approved");
            }
            return response.transactionId();
        } catch (RestClientResponseException e) {
            throw new BusinessException(declinedCode, HttpStatus.UNPROCESSABLE_ENTITY,
                    operation + " declined by provider (http " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            throw new BusinessException("PAYMENT_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                    "payment provider is unreachable");
        }
    }
}
