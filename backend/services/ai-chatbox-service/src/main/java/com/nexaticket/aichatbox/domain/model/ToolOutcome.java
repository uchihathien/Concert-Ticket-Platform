// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Kết quả một lời gọi tool, dạng mô hình đọc được.
 *
 * <p><b>Hỏng vẫn phải trả về.</b> Bỏ qua một tool lỗi khiến request kế tiếp thiếu kết quả cho một
 * lời gọi đã có, và API từ chối cả request. Quan trọng hơn: mô hình cần <i>biết</i> là hỏng để nói
 * với khách "hệ thống tra cứu đang bận" thay vì bịa ra một trạng thái đơn hàng.
 *
 * @param failed đánh dấu để adapter gắn cờ lỗi theo cách của nhà cung cấp
 */
public record ToolOutcome(String callId, String payload, boolean failed) {

    public static ToolOutcome ok(String callId, String payload) {
        return new ToolOutcome(callId, payload, false);
    }

    public static ToolOutcome error(String callId, String message) {
        return new ToolOutcome(callId, message, true);
    }
}
