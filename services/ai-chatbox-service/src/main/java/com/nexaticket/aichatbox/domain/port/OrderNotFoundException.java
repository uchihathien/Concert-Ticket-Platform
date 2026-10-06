// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import java.util.UUID;

/**
 * Không có đơn nào như vậy <b>cho người đang hỏi</b>.
 *
 * <p>Gộp "không tồn tại" và "không phải của bạn" vào một lỗi là có chủ ý: tách ra thì kẻ tấn công
 * dò được mã đơn nào có thật bằng cách đọc thông báo lỗi khác nhau.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("Không tìm thấy đơn " + orderId + " cho người dùng hiện tại");
    }
}
