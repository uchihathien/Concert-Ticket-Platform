// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Nguyên tắc bốn mắt và vòng đời lô chi trả. */
class PayoutBatchTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();

    @Test
    @DisplayName("Người tạo lô KHÔNG được tự duyệt")
    void khong_duoc_tu_duyet() {
        var batch = newBatch();

        assertThatThrownBy(() -> batch.approve(CREATOR, NOW)).isInstanceOf(PayoutBatch.SelfApprovalException.class);
        assertThat(batch.status()).isEqualTo(PayoutStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("Người khác duyệt thì được, và ghi lại ai duyệt")
    void nguoi_khac_duyet_duoc() {
        var batch = newBatch();

        batch.approve(APPROVER, NOW);

        assertThat(batch.status()).isEqualTo(PayoutStatus.APPROVED);
        assertThat(batch.approvedBy()).isEqualTo(APPROVER);
        assertThat(batch.approvedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Không bỏ qua được bước duyệt để xác nhận đã chuyển")
    void khong_bo_qua_duoc_buoc_duyet() {
        // Bỏ qua duyệt nghĩa là một người tự tạo lô rồi tự đánh dấu đã chuyển, và nguyên tắc
        // bốn mắt biến mất.
        var batch = newBatch();

        assertThatThrownBy(() -> batch.markCompleted("FT123", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Duyệt hai lần không được")
    void duyet_hai_lan_khong_duoc() {
        var batch = newBatch();
        batch.approve(APPROVER, NOW);

        assertThatThrownBy(() -> batch.approve(UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Lô đã hoàn tất không quay lại được trạng thái nào khác")
    void da_hoan_tat_thi_khong_quay_lai() {
        // Tiền đã rời tài khoản ký quỹ. Sửa sai phải là bút toán đảo trong sổ cái, không phải
        // sửa trạng thái ở đây.
        var batch = newBatch();
        batch.approve(APPROVER, NOW);
        batch.markCompleted("FT123", NOW);

        assertThatThrownBy(() -> batch.reject("đổi ý")).isInstanceOf(IllegalStateException.class);
        assertThat(batch.status()).isEqualTo(PayoutStatus.COMPLETED);
    }

    @Test
    @DisplayName("Số tiền không dương bị chặn ngay khi tạo")
    void so_tien_khong_duong() {
        assertThatThrownBy(() -> PayoutBatch.create(UUID.randomUUID(), UUID.randomUUID(), 0L, CREATOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PayoutBatch newBatch() {
        return PayoutBatch.create(UUID.randomUUID(), UUID.randomUUID(), 100_000_000L, CREATOR);
    }
}
