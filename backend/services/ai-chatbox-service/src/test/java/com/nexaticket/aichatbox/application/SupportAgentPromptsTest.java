// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.SupportAgentPrompts;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Không câu nào gửi cho khách chứa khoảng trắng thừa giữa câu.
 *
 * <p>Đã xảy ra thật: một text block dùng {@code \} để nối dòng bị Spotless gộp lại, và phần thụt lề
 * của dòng sau trở thành MỘT DÃY KHOẢNG TRẮNG giữa câu — khách đọc được "ngay        tại đây" trong
 * khi mã nguồn nhìn hoàn toàn bình thường. Đó là lớp lỗi chỉ thấy khi đọc output, không thấy khi đọc
 * mã, và nó đi thẳng ra mặt người dùng.
 *
 * <p>Quét bằng reflection thay vì liệt kê từng hàm: câu mới thêm vào ngày mai cũng được kiểm, không
 * cần ai nhớ bổ sung test.
 */
class SupportAgentPromptsTest {

    @Test
    void khong_cau_nao_co_khoang_trang_thua() throws Exception {
        int checked = 0;
        for (Method m : SupportAgentPrompts.class.getDeclaredMethods()) {
            // CHỈ những hàm `*Message()` — đó là quy ước cho câu HIỂN THỊ CHO KHÁCH. `systemPrompt()`
            // cũng trả String nhưng nó đi vào mô hình, nơi khoảng trắng và thụt lề là định dạng có
            // chủ đích; bắt nó ở đây là báo động giả và sẽ dạy người đọc bỏ qua test này.
            if (!Modifier.isPublic(m.getModifiers())
                    || !Modifier.isStatic(m.getModifiers())
                    || m.getParameterCount() != 0
                    || m.getReturnType() != String.class
                    || !m.getName().endsWith("Message")) {
                continue;
            }
            String text = (String) m.invoke(null);
            checked++;
            assertThat(text)
                    .as("%s() có khoảng trắng thừa — xem text block nối dòng bằng dấu gạch chéo", m.getName())
                    .doesNotContain("  ");
            assertThat(text)
                    .as("%s() có dòng kết thúc bằng khoảng trắng", m.getName())
                    .doesNotContain(" \n");
        }
        assertThat(checked)
                .as("không quét được câu nào — quy ước đặt tên *Message đã đổi?")
                .isGreaterThanOrEqualTo(3);
    }
}
