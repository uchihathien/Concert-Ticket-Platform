// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.UUID;

/**
 * Quy định của một sự kiện: độ tuổi, vật phẩm được mang vào, giờ mở cửa.
 *
 * <p><b>Đây là tri thức của chính context này, không phải dữ liệu của catalog.</b> catalog-service
 * mô hình hoá sự kiện, suất diễn, hạng vé và sơ đồ chỗ — nó không có khái niệm "quy định". Nội dung
 * này do đội vận hành soạn và nạp vào {@code ai_chatbox_db}, nên tra nó là một truy vấn cục bộ chứ
 * không phải một lời gọi liên service.
 */
public record EventRules(UUID eventId, String eventTitle, String content) {}
