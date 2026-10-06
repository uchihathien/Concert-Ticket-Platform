// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.HandoffIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Nhận ra câu "cho tôi gặp người thật".
 *
 * <p>Đây là chốt chặn <b>không đi qua mô hình</b>, nên nó phải đúng một cách xác định: cùng một câu
 * luôn cho cùng một kết quả, và kết quả ấy kiểm được mà không gọi API nào.
 */
class HandoffIntentTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cho tôi gặp nhân viên",
                "Cho tôi gặp nhân viên với ạ",
                "mình muốn gặp người thật",
                "chuyển cho nhân viên giúp mình",
                "tôi không muốn chat với bot nữa",
                "I want to talk to a human",
                "can I speak to a real person?"
            })
    @DisplayName("khách đòi gặp người thì nhận ra ngay, không cần hỏi mô hình")
    void nhan_ra_yeu_cau_ro_rang(String message) {
        assertThat(HandoffIntent.isExplicitRequest(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cho toi gap nhan vien", "minh muon gap nguoi that", "khong muon chat voi bot"})
    @DisplayName("gõ không dấu vẫn nhận ra — trên điện thoại đó là cách gõ thường gặp")
    void nhan_ra_ca_khi_khong_dau(String message) {
        // Chữ đ phải xử lý riêng: nó không phải d có dấu phụ, nên Normalizer một mình không tách
        // được. Thiếu bước ấy thì "không muốn" không khớp "khong muon".
        assertThat(HandoffIntent.isExplicitRequest(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "vé của tôi bao giờ thì có",
                "sự kiện này có cho mang chai nước vào không",
                "mã đơn của mình là 3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "mình muốn đổi ghế khác"
            })
    @DisplayName("câu hỏi thường không bị bắt nhầm — trợ lý vẫn được thử trả lời trước")
    void khong_bat_nham_cau_hoi_thuong(String message) {
        assertThat(HandoffIntent.isExplicitRequest(message)).isFalse();
    }

    @Test
    @DisplayName("rỗng hoặc null không phải là một yêu cầu")
    void rong_khong_phai_yeu_cau() {
        assertThat(HandoffIntent.isExplicitRequest(null)).isFalse();
        assertThat(HandoffIntent.isExplicitRequest("   ")).isFalse();
    }
}
