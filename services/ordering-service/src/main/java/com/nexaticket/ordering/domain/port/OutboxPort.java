// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import com.nexaticket.ordering.domain.model.Order;

/**
 * Phát integration event của Ordering.
 *
 * <p>Là port chứ không gọi thẳng {@code OutboxWriter} để tầng application không phải biết tên
 * exchange và hình dạng payload — và để test khẳng định "có bắn sự kiện gì" mà không phải đọc
 * bảng outbox.
 */
public interface OutboxPort {

    void orderCreated(Order order);

    void orderPaid(Order order);

    /** Hết hạn thanh toán hoặc khách huỷ — Inventory nghe để nhả chỗ. */
    void orderClosed(Order order);
}
