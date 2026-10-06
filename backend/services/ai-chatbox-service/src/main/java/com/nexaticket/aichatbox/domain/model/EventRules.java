// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.UUID;

/**
 * Quy định của một sự kiện: độ tuổi, vật phẩm được mang vào, giờ mở cửa — và chính sách hoàn vé.
 *
 * <p><b>Đây là tri thức của chính context này, không phải dữ liệu của catalog.</b> catalog-service
 * mô hình hoá sự kiện, suất diễn, hạng vé và sơ đồ chỗ — nó không có khái niệm "quy định". Nội dung
 * này do đội vận hành soạn và nạp vào {@code ai_chatbox_db}, nên tra nó là một truy vấn cục bộ chứ
 * không phải một lời gọi liên service.
 *
 * @param refundPolicy phần <b>có cấu trúc</b> của quy định, tách khỏi {@code content} vì nó được
 *     <i>thực thi</i> chứ không chỉ được đọc: tool xin hoàn vé so sánh nó với đơn hàng để từ chối
 *     ngay những yêu cầu ngoài chính sách. Để nó trong văn bản tự do thì mô hình là thứ duy nhất
 *     "đọc" chính sách, và mô hình đọc sai không để lại dấu vết nào.
 */
public record EventRules(UUID eventId, String eventTitle, String content, RefundPolicy refundPolicy) {

    public EventRules {
        refundPolicy = refundPolicy == null ? RefundPolicy.NONE : refundPolicy;
    }
}
