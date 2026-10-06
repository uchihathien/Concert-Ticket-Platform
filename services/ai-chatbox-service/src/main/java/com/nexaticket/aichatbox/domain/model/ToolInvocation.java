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
}
