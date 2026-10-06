// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.interfaces.rest;

import com.nexaticket.ordering.application.command.ConfirmPaymentHandler;
import com.nexaticket.ordering.application.query.OrderQueries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /**
     * Trạng thái của nhiều đơn cùng lúc — ticketing gọi khi dựng danh sách vé cho ban tổ chức.
     *
     * <p>{@code POST} cho một phép đọc, và đó là lựa chọn có chủ đích: danh sách id đi trong body
     * chứ không trên URL. 50 UUID trên query string là ~1.900 ký tự, đủ gần giới hạn thực tế của
     * proxy để thành một lỗi 414 chỉ xuất hiện ở production, với những trang danh sách dài nhất.
     */
    @PostMapping("/statuses")
    public StatusesResponse statuses(@Valid @RequestBody StatusesRequest request) {
        return new StatusesResponse(queries.statusesOf(request.orderIds()));
    }

    /** Trần 200: đây là phép tra theo trang, không phải đường xuất cả cơ sở dữ liệu đơn hàng. */
    public record StatusesRequest(@NotEmpty @Size(max = 200) List<UUID> orderIds) {}

    /** Đơn không tồn tại thì vắng mặt trong map — khác hẳn một trạng thái rỗng. */
    public record StatusesResponse(Map<UUID, String> statuses) {}

    /**
     * payment-service gọi sau khi webhook xác nhận. Idempotent — payOS retry tới khi nhận 2xx.
     *
     * <p>Trả về <b>kết quả</b> chứ không phải 204 rỗng, và khác biệt đó có hệ quả thật: tiền vào
     * một đơn đã đóng không còn là 500 nữa mà là 200 kèm {@code MANUAL_REVIEW}. Bên kia cần phân
     * biệt "đã xử lý xong, đừng giao lại" với "hỏng tạm thời, giao lại đi" — và một mã 500 cho
     * trường hợp đầu khiến payOS retry mãi trong khi payment-service rollback mất dòng nhật ký
     * của một khoản tiền có thật.
     */
    @PostMapping("/{orderId}/confirm-payment")
    public ConfirmPaymentResponse confirmPayment(@PathVariable UUID orderId) {
        return new ConfirmPaymentResponse(confirmPayment.handle(orderId).name());
    }

    /** Tên field là hợp đồng với {@code OrderingHttpAdapter} của payment-service. */
    public record ConfirmPaymentResponse(String outcome) {}
}
