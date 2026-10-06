// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Đơn hàng vừa đặt từ một lần giữ chỗ.
 *
 * <p>Có {@code checkoutUrl} vì đó là thứ duy nhất khách cần làm tiếp: mở trang thanh toán. Không có
 * chuỗi VietQR hay mã tham chiếu ngân hàng — chúng dài, không đọc được trong khung chat, và một mô
 * hình chép lại sai một ký tự là tiền đi lạc.
 *
 * @param paymentExpiresAt hạn thanh toán do ordering quyết (mặc định 15 phút kể từ lúc đặt)
 */
public record PlacedOrder(
        UUID orderId, String orderNumber, long totalVnd, String checkoutUrl, Instant paymentExpiresAt) {}
