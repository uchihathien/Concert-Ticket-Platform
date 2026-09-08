// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.payment.domain.model.PaymentReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mã tham chiếu — sợi dây duy nhất nối một giao dịch ngân hàng với một đơn hàng.
 *
 * <p>Ngân hàng không gửi lại orderId hay userId; chỉ có mấy chục ký tự trong ô "nội dung". Mọi
 * test ở đây đều là về việc sợi dây đó không đứt.
 */
class PaymentReferenceTest {

    @Test
    @DisplayName("Bảng chữ cái bỏ I, L, O, U — những ký tự khách gõ nhầm khi chép tay")
    void bang_chu_cai_khong_co_ky_tu_de_nham() {
        for (int i = 0; i < 500; i++) {
            String value = PaymentReference.generate().value();
            assertThat(value).matches("NT[0-9A-Z]{8}");
            assertThat(value.substring(2)).doesNotContain("I", "L", "O", "U");
        }
    }

    @Test
    @DisplayName("Tìm được mã lẫn trong nội dung chuyển khoản thật của ngân hàng")
    void tim_duoc_ma_trong_noi_dung_lon_xon() {
        // Ô nội dung về tới ta hiếm khi sạch: ngân hàng chèn thêm tên người chuyển, mã giao
        // dịch nội bộ, hoặc chữ "CK" ở đầu.
        assertThat(PaymentReference.findIn("CK NTK7M2QP9X GD 123456")
                        .orElseThrow()
                        .value())
                .isEqualTo("NTK7M2QP9X");
        assertThat(PaymentReference.findIn("NGUYEN VAN A chuyen tien NTK7M2QP9X")
                        .orElseThrow()
                        .value())
                .isEqualTo("NTK7M2QP9X");
    }

    @Test
    @DisplayName("Chữ thường vẫn tìm ra — nhiều app ngân hàng đổi hoa thường tuỳ ý")
    void chu_thuong_van_tim_ra() {
        assertThat(PaymentReference.findIn("ck ntk7m2qp9x").orElseThrow().value())
                .isEqualTo("NTK7M2QP9X");
    }

    @Test
    @DisplayName("Không tìm thấy thì trả rỗng, KHÔNG ném")
    void khong_tim_thay_thi_tra_rong() {
        // "Không đọc được mã" là một nhánh nghiệp vụ hợp lệ (tiền đã về, phải sang
        // MANUAL_REVIEW), không phải lỗi lập trình.
        assertThat(PaymentReference.findIn("THANH TOAN VE CONCERT")).isEmpty();
        assertThat(PaymentReference.findIn(null)).isEmpty();
        assertThat(PaymentReference.findIn("")).isEmpty();
    }
}
