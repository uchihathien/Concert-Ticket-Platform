// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.handoff.SupportIntentClassifier;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Gắn nhãn ý định cho phiếu — so chuỗi, không hỏi mô hình, nên phải xác định.
 */
class SupportIntentClassifierTest {

    @ParameterizedTest
    @CsvSource({
        "tôi muốn hoàn tiền vé đã mua, REFUND",
        "cho mình đổi vé sang suất tối được không, REFUND",
        "đã thanh toán rồi mà chưa nhận được vé, INCIDENT",
        "mã QR không quét được ở cổng, INCIDENT",
        "tôi muốn khiếu nại thái độ nhân viên soát vé, COMPLAINT",
        "đơn của tôi sao rồi, ORDER_STATUS",
        "còn vé không cho mình đặt 2 vé, BOOKING",
        "sự kiện diễn khi nào và ở đâu, EVENT_INFO",
        "alo, GENERAL"
    })
    @DisplayName("mỗi câu một nhãn, đúng nhóm người trực sẽ xử lý")
    void gan_nhan_theo_cau_hoi(String message, SupportIntent expected) {
        assertThat(SupportIntentClassifier.classify(message)).isEqualTo(expected);
    }

    @Test
    void khong_dau_van_nhan_ra() {
        assertThat(SupportIntentClassifier.classify("toi muon hoan tien")).isEqualTo(SupportIntent.REFUND);
        assertThat(SupportIntentClassifier.classify("chua nhan duoc ve")).isEqualTo(SupportIntent.INCIDENT);
    }

    /**
     * Thứ tự ưu tiên là có chủ đích: khách muốn TIỀN thì đó là phiếu hoàn vé, dù lý do là một sự
     * cố. Người xử lý hoàn tiền mới là người giải quyết được việc này.
     */
    @Test
    void hoan_tien_vi_su_co_van_la_phieu_hoan_tien() {
        assertThat(SupportIntentClassifier.classify("QR không quét được nên tôi muốn hoàn tiền"))
                .isEqualTo(SupportIntent.REFUND);
    }

    @Test
    void cau_rong_hoac_null_thi_la_general() {
        assertThat(SupportIntentClassifier.classify(null)).isEqualTo(SupportIntent.GENERAL);
        assertThat(SupportIntentClassifier.classify("   ")).isEqualTo(SupportIntent.GENERAL);
    }
}
