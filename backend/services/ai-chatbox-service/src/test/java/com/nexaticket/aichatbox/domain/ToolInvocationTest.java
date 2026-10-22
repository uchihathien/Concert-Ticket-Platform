// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tham số mô hình sinh ra — dữ liệu chưa tin được, kể cả {@code null} và số dạng thực. */
class ToolInvocationTest {

    private static ToolInvocation call(Map<String, Object> args) {
        return new ToolInvocation("c1", "findEvents", args);
    }

    /** Trước đây {@code Map.copyOf} ném NPE ở đây — ngoài khối bắt lỗi của adapter, thành 500. */
    @Test
    void tham_so_null_bi_bo_khong_ném() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "Trịnh");
        args.put("city", null);

        ToolInvocation call = call(args);

        assertThat(call.stringArg("city")).isNull();
        assertThat(call.stringArg("query")).isEqualTo("Trịnh");
        assertThat(call.arguments()).containsOnlyKeys("query");
    }

    @Test
    void arguments_null_thanh_rong() {
        assertThat(call(null).arguments()).isEmpty();
    }

    @Test
    void so_thuc_phan_le_bang_khong_la_so_nguyen() {
        assertThat(call(Map.of("quantity", 2.0)).intArg("quantity")).isEqualTo(2);
        assertThat(call(Map.of("quantity", 2L)).intArg("quantity")).isEqualTo(2);
        assertThat(call(Map.of("quantity", "2.0")).intArg("quantity")).isEqualTo(2);
        assertThat(call(Map.of("quantity", " 3 ")).intArg("quantity")).isEqualTo(3);
    }

    /** Một nửa vé không phải hai vé — không cắt, trả null để hỏi lại khách. */
    @Test
    void so_thuc_co_phan_le_tra_null() {
        assertThat(call(Map.of("quantity", 2.5)).intArg("quantity")).isNull();
        assertThat(call(Map.of("quantity", "2.5")).intArg("quantity")).isNull();
    }

    @Test
    void khong_phai_so_tra_null() {
        assertThat(call(Map.of("quantity", "hai")).intArg("quantity")).isNull();
        assertThat(call(Map.of()).intArg("quantity")).isNull();
    }
}
