// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import com.nexaticket.ordering.application.command.ConfirmPaymentHandler;
import com.nexaticket.ordering.application.query.OrderQueries;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service — chỉ service nội bộ gọi được.
 *
 * <p>Gateway không route {@code /internal/**} ra ngoài. Ở đây đặc biệt quan trọng: endpoint
 * {@code confirm-payment} chuyển đơn sang PAID, và để lọt ra internet là ai cũng phát hành vé
 * được mà không cần trả tiền.
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderQueries queries;
    private final ConfirmPaymentHandler confirmPayment;

    public InternalOrderController(OrderQueries queries, ConfirmPaymentHandler confirmPayment) {
        this.queries = queries;
        this.confirmPayment = confirmPayment;
    }

    /** Ledger và Payout tra chứng từ gốc; bản này có hoa hồng, khác bản của khách. */
    @GetMapping("/{orderId}")
    public OrderQueries.InternalOrderView get(@PathVariable UUID orderId) {
        return queries.internalById(orderId);
    }

    /** payment-service gọi sau khi webhook xác nhận. Idempotent — SePay retry tới khi nhận 2xx. */
    @PostMapping("/{orderId}/confirm-payment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPayment(@PathVariable UUID orderId) {
        confirmPayment.handle(orderId);
    }
}
