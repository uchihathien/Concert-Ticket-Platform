// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.Map;

/**
 * Mô hình yêu cầu chạy một tool.
 *
 * @param callId định danh do nhà cung cấp sinh; kết quả phải mang đúng id này về, nếu không mô
 *     hình không ghép được kết quả với lời gọi
 * @param arguments tham số mô hình sinh ra — <b>dữ liệu chưa tin được</b>. Kiểu {@code Object} là
 *     cố ý: mô hình có thể trả số ở chỗ ta chờ chuỗi. Ép kiểu và kiểm tra là việc của tầng thực
 *     thi tool, không phải của chỗ này.
 */
public record ToolInvocation(String callId, String toolName, Map<String, Object> arguments) {

    public ToolInvocation {
        arguments = Map.copyOf(arguments);
    }

    /** Đọc một tham số dạng chuỗi. Trả null khi thiếu — người gọi quyết định thiếu thì sao. */
    public String stringArg(String name) {
        Object value = arguments.get(name);
        return value == null ? null : value.toString();
    }

    /**
     * Đọc một tham số dạng số nguyên.
     *
     * <p>Nhận cả số lẫn chuỗi số: mô hình khai {@code "quantity": "2"} thường như {@code 2}, và hai
     * cách ấy cùng nghĩa là hai vé. Trả null khi thiếu hoặc không phải số — người gọi tự quyết, vì
     * "không rõ số lượng" là câu nên hỏi lại khách chứ không phải lỗi hệ thống.
     */
    public Integer intArg(String name) {
        Object value = arguments.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
