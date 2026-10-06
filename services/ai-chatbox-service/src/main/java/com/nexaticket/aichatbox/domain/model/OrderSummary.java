// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đơn hàng nhìn từ phía hỗ trợ khách hàng — Anti-Corruption Layer với ordering-service.
 *
 * <p>Chỉ có những trường <b>khách được thấy</b>, và ít hơn cả những gì ordering trả về: mã QR,
 * đường thanh toán và mã tham chiếu ngân hàng bị bỏ lại ở adapter. Chúng không giúp trả lời câu
 * hỏi nào, mà mỗi trường đưa vào prompt là một trường mô hình có thể đọc to lên nhầm chỗ.
 *
 * <p>Bản nội bộ của ordering ({@code /internal/orders/{id}}) có hoa hồng nền tảng và không kiểm
 * chủ sở hữu; nó dành cho sổ cái và chi trả — xem {@code OrderingClientPort}.
 */
public record OrderSummary(
        UUID orderId,
        String orderNumber,
        String status,
        long totalVnd,
        Instant paymentExpiresAt,
        Instant paidAt,
        List<Item> items) {

    public OrderSummary {
        items = List.copyOf(items);
    }

    /** Mỗi mục là một chỗ ngồi hoặc một vé đứng, nên không có số lượng. */
    public record Item(String description, long unitPriceVnd) {}
}
