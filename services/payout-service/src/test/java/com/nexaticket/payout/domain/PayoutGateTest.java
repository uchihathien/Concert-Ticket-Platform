// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.payout.domain.model.PayoutGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sáu cổng chặn trước khi chi trả (services.md §8).
 *
 * <p>Đây là danh sách kiểm cuối cùng trước khi tiền rời tài khoản ký quỹ. Mỗi cổng ứng với một
 * cách mất tiền, nên bộ test này kiểm từng cổng riêng lẻ chứ không chỉ kiểm đường thành công.
 */
class PayoutGateTest {

    private static final long AMOUNT = 100_000_000L;
    private static final String LEGAL_NAME = "Công ty TNHH Giải trí ABC";

    @Test
    @DisplayName("Đủ mọi điều kiện: cho chi trả")
    void du_dieu_kien() {
        assertThat(gate(AMOUNT, AMOUNT, 0, true, true, true, LEGAL_NAME).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("Cổng 1 — chưa qua hạn giữ tiền")
    void chua_qua_han_giu_tien() {
        // Chi trước khi hết hạn hoàn vé thì tiền hoàn cho khách lấy từ đâu.
        assertThat(gate(AMOUNT, AMOUNT, 0, false, true, true, LEGAL_NAME).blockers())
                .contains(PayoutGate.HOLD_PERIOD_NOT_ELAPSED);
    }

    @Test
    @DisplayName("Cổng 2 — chi quá số dư khả dụng")
    void qua_so_du() {
        // Chi quá số dư là lấy tiền của tổ chức khác trả cho tổ chức này, và sổ sách sẽ không
        // bao giờ khớp lại được.
        assertThat(gate(AMOUNT, AMOUNT - 1, 0, true, true, true, LEGAL_NAME).blockers())
                .contains(PayoutGate.INSUFFICIENT_BALANCE);
    }

    @Test
    @DisplayName("Cổng 3 — đối soát hôm trước chưa đóng")
    void doi_soat_chua_dong() {
        assertThat(gate(AMOUNT, AMOUNT, 0, true, false, true, LEGAL_NAME).blockers())
                .contains(PayoutGate.RECONCILIATION_PENDING);
    }

    @Test
    @DisplayName("Cổng 4 — tổ chức bị đình chỉ")
    void to_chuc_bi_dinh_chi() {
        // Đình chỉ thường vì nghi ngờ gian lận; chi tiếp là chuyển tiền ra ngoài tầm với.
        assertThat(gate(AMOUNT, AMOUNT, 0, true, true, false, LEGAL_NAME).blockers())
                .contains(PayoutGate.ORGANIZATION_SUSPENDED);
    }

    @Test
    @DisplayName("Cổng 5 — còn khoản phải thu treo")
    void con_khoan_phai_thu() {
        assertThat(gate(AMOUNT, AMOUNT, 5_000_000L, true, true, true, LEGAL_NAME)
                        .blockers())
                .contains(PayoutGate.OUTSTANDING_RECEIVABLE);
    }

    @Test
    @DisplayName("Cổng 6 — tên chủ tài khoản không khớp hồ sơ pháp nhân")
    void ten_chu_tai_khoan_khong_khop() {
        assertThat(gate(AMOUNT, AMOUNT, 0, true, true, true, "Công ty TNHH XYZ").blockers())
                .contains(PayoutGate.PAYOUT_ACCOUNT_NAME_MISMATCH);
    }

    @Test
    @DisplayName("So khớp tên bỏ qua dấu, hoa thường và khoảng trắng")
    void so_khop_ten_bo_qua_dau() {
        // Ngân hàng trả tên viết hoa không dấu, hồ sơ pháp nhân thì có dấu. So khớp nguyên văn
        // sẽ chặn gần như mọi lần chi trả hợp lệ.
        assertThat(PayoutGate.evaluate(AMOUNT, AMOUNT, 0, true, true, true, "CONG TY TNHH GIAI TRI ABC", LEGAL_NAME)
                        .allowed())
                .isTrue();
        assertThat(PayoutGate.evaluate(AMOUNT, AMOUNT, 0, true, true, true, "  cong ty tnhh giai tri abc  ", LEGAL_NAME)
                        .allowed())
                .isTrue();
    }

    @Test
    @DisplayName("Nhiều cổng hỏng cùng lúc: liệt kê hết, không dừng ở cổng đầu")
    void liet_ke_het_cong_hong() {
        var decision = gate(AMOUNT, 0, 1_000L, false, false, false, "Sai ten");

        assertThat(decision.blockers()).hasSize(6);
    }

    @SuppressWarnings("java:S107")
    private static PayoutGate.Decision gate(
            long requested,
            long available,
            long receivable,
            boolean holdElapsed,
            boolean reconClosed,
            boolean orgActive,
            String accountHolder) {
        return PayoutGate.evaluate(
                requested, available, receivable, holdElapsed, reconClosed, orgActive, accountHolder, LEGAL_NAME);
    }
}
