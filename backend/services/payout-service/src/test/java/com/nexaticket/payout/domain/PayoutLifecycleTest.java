// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutGate;
import com.nexaticket.payout.domain.model.PayoutStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Những chuyển trạng thái và cổng chặn mà {@link PayoutBatchTest} và {@link PayoutGateTest} chưa
 * chạm tới: từ chối, lô đã kết thúc, dựng lại từ database, và các biên của so khớp tên.
 */
class PayoutLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final UUID CREATOR = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID APPROVER = UUID.fromString("a0000000-0000-4000-8000-000000000002");

    @Nested
    @DisplayName("Tạo lô")
    class TaoLo {

        @Test
        @DisplayName("Lô mới: chờ duyệt, chưa có người duyệt, giữ nguyên số tiền và người tạo")
        void lo_moi() {
            UUID org = UUID.randomUUID();
            UUID account = UUID.randomUUID();

            PayoutBatch batch = PayoutBatch.create(org, account, 1L, CREATOR);

            assertThat(batch.status()).isEqualTo(PayoutStatus.PENDING_APPROVAL);
            assertThat(batch.organizationId()).isEqualTo(org);
            assertThat(batch.payoutAccountId()).isEqualTo(account);
            assertThat(batch.amountVnd()).isEqualTo(1L);
            assertThat(batch.createdBy()).isEqualTo(CREATOR);
            assertThat(batch.approvedBy()).isNull();
            assertThat(batch.bankReference()).isNull();
        }

        @Test
        @DisplayName("Số tiền âm bị chặn ngay khi tạo")
        void so_tien_am() {
            assertThatThrownBy(() -> PayoutBatch.create(UUID.randomUUID(), UUID.randomUUID(), -1L, CREATOR))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Dựng lại từ database giữ cả người duyệt LẪN thời điểm duyệt")
        void dung_lai_giu_moc_duyet() {
            PayoutBatch batch = PayoutBatch.rehydrate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    5_000L,
                    CREATOR,
                    PayoutStatus.APPROVED,
                    APPROVER,
                    NOW);

            assertThat(batch.approvedBy()).isEqualTo(APPROVER);
            assertThat(batch.approvedAt()).isEqualTo(NOW);

            batch.markCompleted("FT1", NOW.plusSeconds(60));
            assertThat(batch.status()).isEqualTo(PayoutStatus.COMPLETED);
            assertThat(batch.approvedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("Từ chối")
    class TuChoi {

        @Test
        @DisplayName("Lô chờ duyệt bị từ chối: REJECTED, giữ lý do")
        void tu_choi_lo_cho_duyet() {
            PayoutBatch batch = pending();

            batch.reject("Sai số tài khoản");

            assertThat(batch.status()).isEqualTo(PayoutStatus.REJECTED);
            assertThat(batch.rejectedReason()).isEqualTo("Sai số tài khoản");
        }

        @Test
        @DisplayName("Lô đã duyệt không từ chối được nữa")
        void da_duyet_khong_tu_choi() {
            PayoutBatch batch = pending();
            batch.approve(APPROVER, NOW);

            assertThatThrownBy(() -> batch.reject("đổi ý")).isInstanceOf(IllegalStateException.class);
            assertThat(batch.status()).isEqualTo(PayoutStatus.APPROVED);
        }

        @Test
        @DisplayName("Lô đã từ chối không duyệt được, không xác nhận chuyển được")
        void da_tu_choi_la_ket_thuc() {
            PayoutBatch batch = pending();
            batch.reject("Sai số tài khoản");

            assertThatThrownBy(() -> batch.approve(APPROVER, NOW)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> batch.markCompleted("FT1", NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(batch.status()).isEqualTo(PayoutStatus.REJECTED);
            assertThat(batch.approvedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("Xác nhận đã chuyển")
    class XacNhan {

        @Test
        @DisplayName("Lô đã duyệt: COMPLETED, ghi mã giao dịch và thời điểm")
        void xac_nhan() {
            PayoutBatch batch = pending();
            batch.approve(APPROVER, NOW);

            batch.markCompleted("FT26090812345", NOW.plusSeconds(3600));

            assertThat(batch.status()).isEqualTo(PayoutStatus.COMPLETED);
            assertThat(batch.bankReference()).isEqualTo("FT26090812345");
            assertThat(batch.completedAt()).isEqualTo(NOW.plusSeconds(3600));
        }

        @Test
        @DisplayName("Xác nhận hai lần không được — mã giao dịch đầu tiên không bị ghi đè")
        void xac_nhan_hai_lan() {
            PayoutBatch batch = pending();
            batch.approve(APPROVER, NOW);
            batch.markCompleted("FT-1", NOW);

            assertThatThrownBy(() -> batch.markCompleted("FT-2", NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(batch.bankReference()).isEqualTo("FT-1");
        }

        @Test
        @DisplayName("Người tự duyệt bị chặn TRƯỚC khi đổi trạng thái — lô vẫn duyệt được bởi người khác")
        void tu_duyet_khong_lam_hong_lo() {
            PayoutBatch batch = pending();

            assertThatThrownBy(() -> batch.approve(CREATOR, NOW)).isInstanceOf(PayoutBatch.SelfApprovalException.class);
            batch.approve(APPROVER, NOW);

            assertThat(batch.approvedBy()).isEqualTo(APPROVER);
        }
    }

    @Nested
    @DisplayName("Biên của cổng chặn")
    class BienCongChan {

        private static final String LEGAL = "Công ty TNHH Đông Á";

        @Test
        @DisplayName("Chi đúng bằng số dư: cho; hơn một đồng: chặn")
        void dung_bang_so_du() {
            assertThat(gate(1_000, 1_000, LEGAL).allowed()).isTrue();
            assertThat(gate(1_001, 1_000, LEGAL).blockers()).containsExactly(PayoutGate.INSUFFICIENT_BALANCE);
        }

        @Test
        @DisplayName("Số dư âm (đã chi vượt trước đó): mọi khoản chi đều bị chặn")
        void so_du_am() {
            assertThat(gate(1, -500, LEGAL).blockers()).contains(PayoutGate.INSUFFICIENT_BALANCE);
        }

        @Test
        @DisplayName("Chữ Đ/đ của tiếng Việt được quy về D khi so tên")
        void chu_d_tieng_viet() {
            assertThat(gate(1, 1, "CONG TY TNHH DONG A").allowed()).isTrue();
        }

        @Test
        @DisplayName("Tên khác nhau chỉ ở dấu câu và hậu tố: vẫn khớp; khác một chữ: chặn")
        void dau_cau_va_mot_chu() {
            assertThat(gate(1, 1, "Cong ty TNHH, Dong-A.").allowed()).isTrue();
            assertThat(gate(1, 1, "Cong ty TNHH Dong B").blockers())
                    .containsExactly(PayoutGate.PAYOUT_ACCOUNT_NAME_MISMATCH);
        }

        @Test
        @DisplayName("Hồ sơ không đọc được tên (rỗng): không được coi là khớp với tên chủ tài khoản")
        void ten_ho_so_rong() {
            assertThat(PayoutGate.evaluate(1, 1, 0, true, true, true, "CONG TY TNHH DONG A", "")
                            .blockers())
                    .contains(PayoutGate.PAYOUT_ACCOUNT_NAME_MISMATCH);
        }

        @Test
        @DisplayName("Hai tên KHÁC NHAU không có chữ Latin nào không được coi là khớp")
        void ten_khong_latin_khac_nhau() {
            // Bước chuẩn hoá xoá mọi ký tự ngoài A-Z0-9: "株式会社山田" và "有限公司王" cùng thành
            // chuỗi rỗng. Hai chuỗi rỗng bằng nhau, nên cổng 6 mở cho một tài khoản đứng tên người khác.
            assertThat(PayoutGate.evaluate(1, 1, 0, true, true, true, "有限公司王", "株式会社山田")
                            .blockers())
                    .contains(PayoutGate.PAYOUT_ACCOUNT_NAME_MISMATCH);
        }

        private PayoutGate.Decision gate(long requested, long available, String accountHolder) {
            return PayoutGate.evaluate(requested, available, 0, true, true, true, accountHolder, LEGAL);
        }
    }

    private static PayoutBatch pending() {
        return PayoutBatch.create(UUID.randomUUID(), UUID.randomUUID(), 50_000_000L, CREATOR);
    }
}
