// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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

    /**
     * Bỏ tham số {@code null} TRƯỚC {@code Map.copyOf}.
     *
     * <p>Mô hình gửi {@code {"city": null}} cho tham số tuỳ chọn là chuyện thường, và {@code
     * Map.copyOf} ném NPE với giá trị null — nằm ngoài mọi khối bắt lỗi của adapter, nên khách nhận
     * 500 mà không có phiếu, không có lịch sử. Null và "không khai" nghĩa như nhau với mọi tool.
     */
    public ToolInvocation {
        Map<String, Object> present = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                if (key != null && value != null) {
                    present.put(key, value);
                }
            });
        }
        arguments = Map.copyOf(present);
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
     *
     * <p>{@code 2.0} (số hay chuỗi) là 2 — Ollama trả số dạng thực cho tham số nguyên. {@code 2.5}
     * thì trả null thay vì cắt thành 2: một nửa vé không phải hai vé, và đoán thay khách là sai.
     */
    public Integer intArg(String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        BigDecimal number;
        try {
            number = value instanceof Number n
                    ? new BigDecimal(n.toString())
                    : new BigDecimal(value.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
        try {
            return number.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
