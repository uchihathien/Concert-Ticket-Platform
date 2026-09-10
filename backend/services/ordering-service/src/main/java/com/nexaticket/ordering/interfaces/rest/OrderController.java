// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import com.nexaticket.ordering.application.command.CloseOrderHandler;
import com.nexaticket.ordering.application.command.PlaceOrderHandler;
import com.nexaticket.ordering.application.query.OrderQueries;
import com.nexaticket.ordering.application.query.OrderView;
import com.nexaticket.platform.security.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đơn hàng của khách.
 *
 * <p>{@code POST} bắt buộc có {@code Idempotency-Key} — filter của starter-idempotency chặn trước
 * khi tới đây. Đó là lưới thứ nhất chống bấm hai lần; lưới thứ hai là tra đơn theo {@code holdId},
 * lưới thứ ba là unique index {@code orders.hold_id}.
 */
@RestController
@RequestMapping("/v1")
public class OrderController {

    private final PlaceOrderHandler placeOrder;
    private final CloseOrderHandler closeOrder;
    private final OrderQueries queries;

    public OrderController(PlaceOrderHandler placeOrder, CloseOrderHandler closeOrder, OrderQueries queries) {
        this.placeOrder = placeOrder;
        this.closeOrder = closeOrder;
        this.queries = queries;
    }

    public record PlaceOrderRequest(@NotNull UUID holdId, String promotionCode) {}

    /**
     * @param checkoutUrl trang thanh toán payOS host — đường chính cho khách (ADR-0016)
     * @param vietQrPayload chuỗi EMVCo — frontend tự render thành QR, nhẹ hơn trả ảnh và không
     *     phụ thuộc dịch vụ ngoài
     */
    public record OrderCreated(
            UUID orderId,
            String orderNumber,
            long totalVnd,
            String paymentReference,
            String vietQrPayload,
            String checkoutUrl,
            Instant paymentExpiresAt) {}

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreated place(@Valid @RequestBody PlaceOrderRequest request) {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        var result =
                placeOrder.handle(new PlaceOrderHandler.Command(request.holdId(), userId, request.promotionCode()));
        return new OrderCreated(
                result.orderId(),
                result.orderNumber(),
                result.totalVnd(),
                result.paymentReference(),
                result.vietQrPayload(),
                result.checkoutUrl(),
                result.paymentExpiresAt());
    }

    @GetMapping("/orders/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return queries.byIdForUser(orderId, currentUser());
    }

    @GetMapping("/me/orders")
    public List<OrderView> mine(
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.forUser(currentUser(), Math.min(limit, 100), offset);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID orderId) {
        closeOrder.cancel(orderId, currentUser());
    }

    private static UUID currentUser() {
        return TenantContext.requireAuthenticated().userId().value();
    }
}
