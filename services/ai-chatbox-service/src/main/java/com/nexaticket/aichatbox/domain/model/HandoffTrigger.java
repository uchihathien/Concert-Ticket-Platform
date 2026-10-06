// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Vì sao cuộc chat được chuyển sang người thật.
 *
 * <p>Để <b>đo</b>, không để hiển thị: người trực đọc {@code reason} viết bằng câu. Hai giá trị này
 * trả lời một câu hỏi khác — tỷ lệ trợ lý tự bỏ cuộc so với tỷ lệ khách chủ động đòi gặp người.
 * Hai con số đó cần xử lý ngược nhau: cái đầu là lỗ hổng tri thức, cái sau là vấn đề niềm tin.
 */
public enum HandoffTrigger {

    /** Khách nói thẳng là muốn gặp người. */
    CUSTOMER_REQUEST,

    /** Trợ lý tự nhận là không trả lời được — gọi tool chuyển tiếp, hoặc hết vòng mà chưa trả lời. */
    LOW_CONFIDENCE
}
