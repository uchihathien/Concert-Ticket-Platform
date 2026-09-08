// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.http;

import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang payment-service. */
@Component
public class PaymentHttpAdapter implements PaymentPort {

    private static final String SERVICE = "payment-service";

    private final RestClient client;

    public PaymentHttpAdapter(@Qualifier("paymentClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Intent openIntent(UUID orderId, UUID organizationId, long totalVnd, Instant expiresAt) {
        try {
            IntentResponse response = client.post()
                    .uri("/internal/payment-intents")
                    .body(Map.of(
                            "orderId", orderId,
                            "organizationId", organizationId,
                            "amountVnd", totalVnd,
                            "expiresAt", expiresAt.toString()))
                    .retrieve()
                    .body(IntentResponse.class);
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Payment trả body rỗng", null);
            }
            return new Intent(
                    response.paymentReference(),
                    response.vietQrPayload(),
                    response.bankBin(),
                    response.bankAccountNumber());
        } catch (RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không mở được payment intent cho đơn " + orderId, e);
        }
    }

    @Override
    public void cancelIntent(UUID orderId) {
        try {
            client.delete()
                    .uri("/internal/payment-intents/{orderId}", orderId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không huỷ được payment intent của đơn " + orderId, e);
        }
    }

    record IntentResponse(String paymentReference, String vietQrPayload, String bankBin, String bankAccountNumber) {}
}
