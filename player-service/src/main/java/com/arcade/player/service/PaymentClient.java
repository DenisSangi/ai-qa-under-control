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
 * WireMock stub (docker-compose service "wiremock"): amounts over 10000 are declined.
 */
@Service
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${payment.base-url}") String baseUrl, RestClient.Builder builder) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public record PaymentRequest(Long playerId, BigDecimal amount) {
    }

    public record PaymentResponse(String status, String transactionId, String reason) {
    }

    public String charge(Long playerId, BigDecimal amount) {
        try {
            PaymentResponse response = restClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PaymentRequest(playerId, amount))
                    .retrieve()
                    .body(PaymentResponse.class);
            if (response == null || !"APPROVED".equals(response.status())) {
                throw new BusinessException("PAYMENT_DECLINED", HttpStatus.UNPROCESSABLE_ENTITY,
                        "payment was not approved");
            }
            return response.transactionId();
        } catch (RestClientResponseException e) {
            throw new BusinessException("PAYMENT_DECLINED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "payment declined by provider (http " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            throw new BusinessException("PAYMENT_UNAVAILABLE", HttpStatus.BAD_GATEWAY,
                    "payment provider is unreachable");
        }
    }
}
