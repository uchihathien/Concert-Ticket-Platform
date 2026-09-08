// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.interfaces.rest;

import com.nexaticket.payment.application.command.OpenPaymentIntentHandler;
import com.nexaticket.payment.application.query.PaymentIntentView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service cho saga checkout — chỉ service nội bộ gọi được.
 *
 * <p>Gateway không route {@code /internal/**} ra ngoài. Ở đây bắt buộc: endpoint này sinh mã QR
 * gắn với tài khoản ký quỹ của nền tảng.
 */
@RestController
@RequestMapping("/internal/payment-intents")
public class InternalPaymentController {

    private final OpenPaymentIntentHandler openIntent;

    public InternalPaymentController(OpenPaymentIntentHandler openIntent) {
        this.openIntent = openIntent;
    }

    public record OpenRequest(
            @NotNull UUID orderId,
            @NotNull UUID organizationId,
            @Positive long amountVnd,
            @NotNull Instant expiresAt) {}

    @PostMapping
    public PaymentIntentView open(@RequestBody OpenRequest request) {
        return openIntent.open(new OpenPaymentIntentHandler.Command(
                request.orderId(), request.organizationId(), request.amountVnd(), request.expiresAt()));
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID orderId) {
        openIntent.cancel(orderId);
    }
}
