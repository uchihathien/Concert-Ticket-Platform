// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.List;

/**
 * Khai báo một tool cho mô hình, độc lập với nhà cung cấp.
 *
 * <p>Chỉ đỡ được kiểu chuỗi và số — đủ cho mọi tool của agent này và đủ để adapter dựng JSON Schema
 * mà không cần ai viết schema bằng tay. Cần kiểu lồng nhau thì mở rộng ở đây, đừng để adapter tự
 * bịa: schema là thứ mô hình đọc, nên nó là hợp đồng nghiệp vụ chứ không phải chi tiết kỹ thuật.
 *
 * @param description mô tả cho <b>mô hình</b> đọc, không phải cho lập trình viên. Đây là thứ quyết
 *     định tool có được gọi đúng lúc hay không — mô tả mơ hồ thì mô hình gọi bừa hoặc không gọi.
 */
public record ToolSpec(String name, String description, List<Param> params) {

    public ToolSpec {
        params = List.copyOf(params);
    }

    /** @param type "string" hoặc "integer" — đúng tên kiểu của JSON Schema */
    public record Param(String name, String type, String description, boolean required) {

        public static Param requiredString(String name, String description) {
            return new Param(name, "string", description, true);
        }
    }
}
