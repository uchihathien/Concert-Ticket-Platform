// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.model;

import java.util.Map;

/**
 * Mẫu thư, dựng nội dung từ dữ liệu sự kiện.
 *
 * <p>Nội dung tiếng Việt vì người nhận là khách và ban tổ chức Việt Nam. Điều này khác với
 * {@code detail} của mã lỗi API — cái đó là tiếng Anh cho log.
 *
 * <p><b>Không mẫu nào chứa mã QR vé.</b> Thư chỉ dẫn khách vào ứng dụng để lấy mã, vì mã QR trong
 * email sẽ nằm mãi trong hộp thư và trong mọi bản sao lưu hộp thư đó — trong khi mã trong ứng dụng
 * hết hạn sau vài giờ.
 */
public enum EmailTemplate {
    ORGANIZATION_INVITATION("Lời mời tham gia %s trên NexaTicket"),
    ORDER_PAID("Đơn hàng %s đã thanh toán thành công"),
    ORDER_EXPIRED("Đơn hàng %s đã hết hạn thanh toán"),
    TICKETS_ISSUED("Vé của bạn cho %s đã sẵn sàng");

    private final String subjectPattern;

    EmailTemplate(String subjectPattern) {
        this.subjectPattern = subjectPattern;
    }

    public String subject(Map<String, Object> payload) {
        Object headline = payload.getOrDefault("headline", "");
        return subjectPattern.formatted(headline);
    }

    /**
     * Thân thư dạng chữ thuần.
     *
     * <p>Cố ý không nhúng HTML phức tạp: thư giao dịch phải đọc được ở mọi ứng dụng mail, kể cả
     * bản chỉ hiện chữ, và HTML càng phức tạp thì càng dễ bị lọc vào thư rác.
     */
    public String body(Map<String, Object> payload) {
        return switch (this) {
            case ORGANIZATION_INVITATION -> """
                    Bạn được mời tham gia %s trên NexaTicket.

                    Mở liên kết trong ứng dụng để chấp nhận lời mời.
                    """
                    .formatted(payload.getOrDefault("headline", ""));
            case ORDER_PAID -> """
                    Đơn hàng %s đã được thanh toán.

                    Vé của bạn sẽ xuất hiện trong mục "Vé của tôi" trên ứng dụng.
                    """
                    .formatted(payload.getOrDefault("headline", ""));
            case ORDER_EXPIRED -> """
                    Đơn hàng %s đã hết hạn chuyển khoản và các chỗ đã được nhả.

                    Bạn có thể đặt lại nếu vẫn còn chỗ trống.
                    """
                    .formatted(payload.getOrDefault("headline", ""));
            case TICKETS_ISSUED -> """
                    Vé của bạn cho %s đã sẵn sàng.

                    Mở mục "Vé của tôi" trên ứng dụng để lấy mã QR khi tới cửa.
                    Mã QR chỉ có hiệu lực vài giờ nên hãy mở ứng dụng lúc đi xem.
                    """
                    .formatted(payload.getOrDefault("headline", ""));
        };
    }
}
